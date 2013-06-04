package net.codechunk.speedofsound;

import net.codechunk.speedofsound.util.AppPreferences;
import net.codechunk.speedofsound.util.AverageSpeed;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

/**
 * The main sound control service.
 * 
 * Responsible for adjusting the volume based on the current speed. Can be
 * started and stopped externally, but is largely independent.
 */
public class SoundService extends Service
{
	private static final String TAG = "SoundService";

	/**
	 * Intent extra to set the tracking state.
	 */
	public static final String SET_TRACKING_STATE = "set-tracking-state";

	/**
	 * Broadcast to notify that tracking has started or stopped.
	 */
	public static final String TRACKING_STATE_BROADCAST = "tracking-state-changed";

	/**
	 * Location, speed, and sound update broadcast.
	 */
	public static final String LOCATION_UPDATE_BROADCAST = "location-update";

	/**
	 * The current tracking state. XXX: this should probably not be a public
	 * field
	 */
	public boolean tracking = false;

	private SharedPreferences settings;
	private LocalBroadcastManager localBroadcastManager;
	private SoundServiceManager soundServiceManager = new SoundServiceManager();

	private VolumeThread volumeThread = null;
	private LocationManager locationManager;
	private AverageSpeed averager = new AverageSpeed(6);
	private LocalBinder binder = new LocalBinder();
	private SongTracker songTracker;

	/**
	 * Start up the service and initialize some values. Does not start tracking.
	 */
	@Override
	public void onCreate()
	{
		Log.d(TAG, "Service starting up");

		// set up preferences
		AppPreferences.setDefaults(this);
		AppPreferences.runUpgrade(this);
		this.settings = PreferenceManager.getDefaultSharedPreferences(this);
		AppPreferences.updateNativeSpeeds(this.settings);

		// register handlers & audio
		this.localBroadcastManager = LocalBroadcastManager.getInstance(this);
		this.locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
		this.songTracker = SongTracker.getInstance(this);

		// activation broadcasts
		IntentFilter activationFilter = this.soundServiceManager.activationIntents();
		this.registerReceiver(this.soundServiceManager, activationFilter);
	}

	/**
	 * Handle a start command.
	 * 
	 * Return sticky mode to tell Android to keep the service active.
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int startId)
	{
		Log.d(TAG, "Start command received");

		// check if we've been commanded to start or stop tracking
		if (intent != null)
		{
			Bundle extras = intent.getExtras();
			if (extras != null && extras.containsKey(SoundService.SET_TRACKING_STATE))
			{
				Log.v(TAG, "Commanded to change state");
				if (extras.getBoolean(SoundService.SET_TRACKING_STATE))
				{
					this.startTracking();
				}
				else
				{
					this.stopTracking();
				}
			}
		}

		return START_STICKY;
	}

	/**
	 * Service shut-down log.
	 */
	@Override
	public void onDestroy()
	{
		Log.d(TAG, "Service shutting down");
	}

	/**
	 * Start tracking. Find the best location provider (likely GPS), create an
	 * ongoing notification, and request location updates.
	 */
	public void startTracking()
	{
		// ignore requests when we're already tracking
		if (this.tracking)
		{
			return;
		}

		// request updates
		Criteria criteria = new Criteria();
		criteria.setAccuracy(Criteria.ACCURACY_FINE);
		String provider = this.locationManager.getBestProvider(criteria, true);
		if (provider != null)
		{
			this.locationManager.requestLocationUpdates(provider, 0, 0, this.locationUpdater);
		}
		else
		{
			Toast toast = Toast.makeText(this, this.getString(R.string.no_location_providers), Toast.LENGTH_LONG);
			toast.show();
			return;
		}

		// start a new route
		this.songTracker.startRoute();

		// start up the volume thread
		if (this.volumeThread == null)
		{
			this.volumeThread = new VolumeThread(this);
			this.volumeThread.start();
		}

		// force foreground with an ongoing notification
		Intent notificationIntent = new Intent(this, SpeedActivity.class);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this);
		builder.setContentTitle(getString(R.string.app_name));
		builder.setContentText(getString(R.string.notification_text));
		builder.setContentIntent(PendingIntent.getActivity(this, 0, notificationIntent, 0));
		builder.setTicker(getString(R.string.ticker_text));
		builder.setSmallIcon(R.drawable.ic_notification);
		builder.setWhen(System.currentTimeMillis());

		// 4.1+ actions
		Intent stopIntent = new Intent(this, SoundService.class);
		stopIntent.putExtra(SoundService.SET_TRACKING_STATE, false);
		builder.addAction(R.drawable.ic_stop, getString(R.string.stop),
				PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_ONE_SHOT));

		// show the notification
		startForeground(R.string.notification_text, builder.build());

		// let everyone know
		Intent intent = new Intent(SoundService.TRACKING_STATE_BROADCAST);
		intent.putExtra("tracking", true);
		SoundService.this.localBroadcastManager.sendBroadcast(intent);

		this.tracking = true;
		Log.d(TAG, "Tracking started with location provider " + provider);
	}

	/**
	 * Stop tracking. Remove the location updates and notification.
	 */
	public void stopTracking()
	{
		// don't do anything if we're not tracking
		if (!this.tracking)
		{
			return;
		}

		// shut off the volume thread
		if (this.volumeThread != null)
		{
			this.volumeThread.interrupt();
			this.volumeThread = null;
		}

		// end the current route
		this.songTracker.endRoute();

		// disable location updates
		this.locationManager.removeUpdates(this.locationUpdater);

		// remove notification and go to background
		stopForeground(true);

		// let everyone know
		Intent intent = new Intent(SoundService.TRACKING_STATE_BROADCAST);
		intent.putExtra("tracking", false);
		SoundService.this.localBroadcastManager.sendBroadcast(intent);

		this.tracking = false;
		Log.d(TAG, "Tracking stopped");
	}

	/**
	 * Update the system volume based on the given speed. Reads preferences
	 * on-the-fly for low and high speeds and volumes. If below the minimum
	 * speed, uses the minimum volume. If above the maximum speed, uses the max
	 * volume. If somewhere in between, use a log scaling function to smoothly
	 * scale the volume.
	 * 
	 * @param speed
	 *            Reference speed to base volume on
	 * @return Set volume (0-100).
	 */
	private int updateVolume(float speed)
	{
		float volume = 0f;

		float lowSpeed = this.settings.getFloat("low_speed", 0);
		int lowVolume = this.settings.getInt("low_volume", 0);
		float highSpeed = this.settings.getFloat("high_speed", 100);
		int highVolume = this.settings.getInt("high_volume", 100);

		if (speed < lowSpeed)
		{
			// minimum volume
			Log.d(TAG, "Low speed triggered at " + speed);
			volume = lowVolume / 100f;
		}
		else if (speed > highSpeed)
		{
			// high volume
			Log.d(TAG, "High speed triggered at " + speed);
			volume = highVolume / 100f;
		}
		else
		{
			// log scaling
			float volumeRange = (highVolume - lowVolume) / 100f;
			float speedRangeFrac = (speed - lowSpeed) / (highSpeed - lowSpeed);
			float volumeRangeFrac = (float) (Math.log1p(speedRangeFrac) / Math.log1p(1));
			volume = lowVolume / 100f + volumeRange * volumeRangeFrac;
			Log.d(TAG, "Log scale triggered with " + speed + ", using volume " + volume);
		}

		// apply the volume
		this.volumeThread.setTargetVolume(volume);
		return (int) (volume * 100);
	}

	/**
	 * Custom location listener. Triggers volume changes based on the current
	 * average speed.
	 */
	private LocationListener locationUpdater = new LocationListener()
	{
		private Location previousLocation = null;

		/**
		 * Change the volume based on the current average speed. If speed is not
		 * available from the current location provider, calculate it from the
		 * previous location. After updating the average and updating the
		 * volume, send out a broadcast notifying of the changes.
		 */
		public void onLocationChanged(Location location)
		{
			// grab the speed
			float speed;

			// use the GPS-provided speed if available
			if (location.hasSpeed())
			{
				speed = location.getSpeed();
			}
			else
			{
				// speed fall-back (mostly for the emulator)
				if (this.previousLocation != null)
				{
					// get the distance between this and the previous update
					float meters = previousLocation.distanceTo(location);
					float timeDelta = location.getTime() - previousLocation.getTime();

					Log.v(TAG, "Location distance: " + meters);

					// convert to meters/second
					speed = 1000 * meters / timeDelta;
				}
				else
				{
					speed = 0;
				}

				this.previousLocation = location;
			}

			// push average to filter out spikes
			Log.v(TAG, "Pushing speed " + speed);
			SoundService.this.averager.push(speed);

			// update the speed
			float avg = SoundService.this.averager.getAverage();
			Log.v(TAG, "Average currently " + avg);
			int volume = SoundService.this.updateVolume(avg);

			// send out a local broadcast with the details
			Intent intent = new Intent(SoundService.LOCATION_UPDATE_BROADCAST);
			intent.putExtra("location", location);
			intent.putExtra("speed", speed);
			intent.putExtra("volume", volume);
			SoundService.this.localBroadcastManager.sendBroadcast(intent);
		}

		public void onProviderDisabled(String provider)
		{
		}

		public void onProviderEnabled(String provider)
		{
		}

		public void onStatusChanged(String provider, int status, Bundle extras)
		{
		}
	};

	/**
	 * Service-level access for external classes and activities.
	 */
	public class LocalBinder extends Binder
	{
		/**
		 * Return the service associated with this binder.
		 */
		public SoundService getService()
		{
			return SoundService.this;
		}
	}

	/**
	 * Return the binder associated with this service.
	 */
	@Override
	public IBinder onBind(Intent intent)
	{
		return this.binder;
	}

}