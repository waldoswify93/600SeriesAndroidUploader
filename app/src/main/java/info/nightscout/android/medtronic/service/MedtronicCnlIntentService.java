package info.nightscout.android.medtronic.service;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.app.TaskStackBuilder;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

import info.nightscout.android.R;
import info.nightscout.android.USB.UsbHidDriver;
import info.nightscout.android.UploaderApplication;
import info.nightscout.android.medtronic.MainActivity;
import info.nightscout.android.medtronic.MedtronicCnlReader;
import info.nightscout.android.medtronic.StatusNotification;
import info.nightscout.android.medtronic.exception.ChecksumException;
import info.nightscout.android.medtronic.exception.EncryptionException;
import info.nightscout.android.medtronic.exception.UnexpectedMessageException;
import info.nightscout.android.medtronic.message.MessageUtils;
import info.nightscout.android.model.medtronicNg.ContourNextLinkInfo;
import info.nightscout.android.model.medtronicNg.PumpInfo;
import info.nightscout.android.model.medtronicNg.PumpStatusEvent;
import info.nightscout.android.upload.nightscout.NightscoutUploadReceiver;
import info.nightscout.android.utils.ConfigurationStore;
import info.nightscout.android.utils.DataStore;
import info.nightscout.android.utils.StatusMessage;
import info.nightscout.android.xdrip_plus.XDripPlusUploadReceiver;
import io.realm.Realm;
import io.realm.RealmResults;
import io.realm.Sort;

import static android.support.v4.app.NotificationCompat.VISIBILITY_PUBLIC;
import static info.nightscout.android.medtronic.MainActivity.MMOLXLFACTOR;

//public class MedtronicCnlIntentService extends Service {
    public class MedtronicCnlIntentService extends IntentService {
    public final static int USB_VID = 0x1a79;
    public final static int USB_PID = 0x6210;
    public final static long USB_WARMUP_TIME_MS = 5000L;

    public final static long POLL_PERIOD_MS = 300000L;
    public final static long LOW_BATTERY_POLL_PERIOD_MS = 900000L;
    // Number of additional seconds to wait after the next expected CGM poll, so that we don't interfere with CGM radio comms.
    public final static long POLL_GRACE_PERIOD_MS = 30000L;
    // Number of seconds before the next expected CGM poll that we will allow uploader comms to start
    public final static long POLL_PRE_GRACE_PERIOD_MS = 45000L;
    // cgm n/a events to trigger anti clash poll timing
    public final static int POLL_ANTI_CLASH = 1; //3

    // TODO - use a message type and insert icon as part of ui status message handling
    public static final String ICON_WARN = "{ion_alert_circled} ";
    public static final String ICON_BGL = "{ion_waterdrop} ";
    public static final String ICON_USB = "{ion_usb} ";
    public static final String ICON_INFO = "{ion_information_circled} ";
    public static final String ICON_HELP = "{ion_ios_lightbulb} ";
    public static final String ICON_SETTING = "{ion_android_settings} ";
    public static final String ICON_HEART = "{ion_heart} ";
    public static final String ICON_LOW = "{ion_battery_low} ";
    public static final String ICON_FULL = "{ion_battery_full} ";
    public static final String ICON_CGM = "{ion_ios_pulse_strong} ";
    public static final String ICON_SUSPEND = "{ion_pause} ";
    public static final String ICON_RESUME = "{ion_play} ";
    public static final String ICON_BOLUS = "{ion_skip_forward} ";
    public static final String ICON_BASAL = "{ion_skip_forward} ";
    public static final String ICON_CHANGE = "{ion_android_hand} ";
    public static final String ICON_BELL = "{ion_android_notifications} ";
    public static final String ICON_NOTE = "{ion_clipboard} ";

    // show warning message after repeated errors
    private final static int ERROR_COMMS_AT = 4;
    private final static int ERROR_CONNECT_AT = 6;
    private final static int ERROR_SIGNAL_AT = 6;
    private final static int ERROR_PUMPLOSTSENSOR_AT = 6;
    private final static int ERROR_PUMPBATTERY_AT = 3;
    private final static int ERROR_PUMPCLOCK_AT = 8;
    private final static int ERROR_PUMPCLOCK_MS = 10 * 60 * 1000;

    private static final String TAG = MedtronicCnlIntentService.class.getSimpleName();

    private UsbHidDriver mHidDevice;
    private Context mContext;
    private UsbManager mUsbManager;
    private DataStore dataStore = DataStore.getInstance();
    private ConfigurationStore configurationStore = ConfigurationStore.getInstance();
    private DateFormat dateFormatter = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private DateFormat dateFormatterNote = new SimpleDateFormat("E HH:mm", Locale.US);
    private Realm realm;
    private long pumpOffset;

    private long testkill = 0;

    public static final int USB_DISCONNECT_NOFICATION_ID = 1;
    private static final int SERVICE_NOTIFICATION_ID = 2;

//    private CnlIntentMessageReceiver cnlIntentMessageReceiver;// = new CnlIntentMessageReceiver();
    private boolean commsActive = false;
    private boolean commsDestroy = false;

    private StatusMessage statusMessage = StatusMessage.getInstance();
    private BatteryReceiver batteryReceiver;// = new BatteryReceiver();
    private UsbReceiver usbReceiver;// = new UsbReceiver();

    private StatusNotification statusNotification = StatusNotification.getInstance();


    public MedtronicCnlIntentService() {
        super(MedtronicCnlIntentService.class.getName());
    }

    protected void sendMessage(String action) {
        try {
            Intent localIntent =
                    new Intent(action);
            LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent);
        } catch (Exception e ) {}
    }

/*
    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
*/
    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "onCreate called");
//        statusMessage.add(TAG + " onCreate called");

        mContext = this.getBaseContext();
        mUsbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
/*
        IntentFilter cnlIntentMessageFilter = new IntentFilter();
        cnlIntentMessageFilter.addAction(Constants.ACTION_READ_PUMP);
        registerReceiver(cnlIntentMessageReceiver, cnlIntentMessageFilter);

        IntentFilter batteryIntentFilter = new IntentFilter();
        batteryIntentFilter.addAction(Intent.ACTION_BATTERY_LOW);
        batteryIntentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
        batteryIntentFilter.addAction(Intent.ACTION_BATTERY_OKAY);
        registerReceiver(batteryReceiver, batteryIntentFilter);

        IntentFilter usbIntentFilter = new IntentFilter();
        usbIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        usbIntentFilter.addAction(MedtronicCnlIntentService.Constants.ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, usbIntentFilter);
        LocalBroadcastManager.getInstance(this).registerReceiver(
                usbReceiver,
                new IntentFilter(MedtronicCnlIntentService.Constants.ACTION_NO_USB_PERMISSION));

        // setup self handling alarm receiver
        MedtronicCnlAlarmManager.setContext(mContext);
*/
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.d(TAG, "onDestroy called");
//        statusMessage.add(TAG + " onDestroy called");

        if (commsActive) {
            Log.d(TAG, "comms are active!!!");
            statusMessage.add(TAG + " comms are active!!!");
            commsDestroy = true;
        } else {

            if (mHidDevice != null) {
                Log.i(TAG, "Closing serial device...");
                mHidDevice.close();
                mHidDevice = null;
            }

//            statusNotification.endNotification();
        }
/*
        MedtronicCnlAlarmManager.cancelAlarm();

        LocalBroadcastManager.getInstance(this).unregisterReceiver(usbReceiver);
        unregisterReceiver(usbReceiver);
        unregisterReceiver(batteryReceiver);
        unregisterReceiver(cnlIntentMessageReceiver);
*/
    }

    @Override
    public void onTaskRemoved(Intent intent) {
        Log.i(TAG, "onTaskRemoved called");
        statusMessage.add(TAG + " onTaskRemoved, comms active=" + commsActive);
    }

    @Override
    public void onLowMemory() {
        Log.i(TAG, "onLowMemory called");
        statusMessage.add(TAG + " onLowMemory, comms active=" + commsActive);
    }
/*
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Received start id " + startId + ": " + intent);
//        statusMessage.add(TAG + " Received start id " + startId + ": " + (intent == null ? "null" : ""));

        if (intent == null) {
            // do nothing and return
            return START_NOT_STICKY;
        }

        getMyShit();

/*
        if (intent == null) {
            // do nothing and return
            return START_STICKY;
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        Notification notification = new  NotificationCompat.Builder(this)
                .setContentTitle("600 Series Uploader")
                .setSmallIcon(R.drawable.ic_launcher)
                .setVisibility(VISIBILITY_PUBLIC)
                .setContentIntent(pendingIntent)
                .setTicker("600 Series Nightscout Uploader")
                .build();
        startForeground(SERVICE_NOTIFICATION_ID, notification);

        statusNotification.initNotification(mContext);

        startCgm();

        Log.i(TAG, "Starting in foreground mode");
        statusMessage.add(TAG + " Starting in foreground mode");

        return START_STICKY;

        return START_NOT_STICKY;
    }
*/
    private void startCgmService() {
        startCgmServiceDelayed(0);
    }

    private void startCgmServiceDelayed(long delay) {
        startCgm();
    }

    private void startCgm() {
        long now = System.currentTimeMillis();
        long start = now + 1000;

        realm = Realm.getDefaultInstance();

        RealmResults<PumpStatusEvent> results = realm.where(PumpStatusEvent.class)
                .greaterThan("eventDate", new Date(System.currentTimeMillis() - (24 * 60 * 1000)))
                .equalTo("validCGM", true)
                .findAllSorted("cgmDate", Sort.DESCENDING);

        if (results.size() > 0) {
            long timeLastCGM = results.first().getCgmDate().getTime();
            if (now - timeLastCGM < MedtronicCnlIntentService.POLL_GRACE_PERIOD_MS + MedtronicCnlIntentService.POLL_PERIOD_MS)
                start = timeLastCGM + MedtronicCnlIntentService.POLL_GRACE_PERIOD_MS + MedtronicCnlIntentService.POLL_PERIOD_MS;
        }

        MedtronicCnlAlarmManager.setAlarm(start);
        statusNotification.updateNotification(start);

        if (!realm.isClosed()) realm.close();
    }

/*
    private class CnlIntentMessageReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            //           String message = intent.getStringExtra(Constants.ACTION_READ_PUMP);
            //           Log.i(TAG, "Message Receiver: " + message);

//            statusMessage.add("* service message");

            getMyShit();
            killer();

        }
    }

    private void killer() {
        if (testkill++ > 3) {
            Log.d(TAG, "kill with fire");
            statusMessage.add("kill with fire");

            if (commsActive) {
                Log.d(TAG, "onDestroy comms are active!!!");
                statusMessage.add("onDestroy comms are active!!!");
                commsDestroy = true;
            } else {

                if (mHidDevice != null) {
                    Log.i(TAG, "Closing serial device...");
                    mHidDevice.close();
                    mHidDevice = null;
                }

                statusNotification.endNotification();
            }

            MedtronicCnlAlarmManager.cancelAlarm();

            LocalBroadcastManager.getInstance(this).unregisterReceiver(usbReceiver);
            unregisterReceiver(usbReceiver);
            unregisterReceiver(batteryReceiver);
            unregisterReceiver(cnlIntentMessageReceiver);

            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    private final static boolean debug_wakelocks = true;

    public static PowerManager.WakeLock getWakeLock(final String name, int millis) {
        final PowerManager pm = (PowerManager) UploaderApplication.getAppContext().getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, name);
        wl.acquire(millis);
        if (debug_wakelocks) Log.d(TAG, "getWakeLock: " + name + " " + wl.toString());
        return wl;
    }

    public static void releaseWakeLock(PowerManager.WakeLock wl) {
        if (debug_wakelocks) Log.d(TAG, "releaseWakeLock: " + wl.toString());
        if (wl.isHeld()) wl.release();
    }

    private static final Object lock = new Object();
*/

/*

Notes on Errors:

CNL-PUMP pairing and registered devices

CNL: paired PUMP: paired UPLOADER: registered = ok
CNL: paired PUMP: paired UPLOADER: unregistered = ok
CNL: paired PUMP: unpaired UPLOADER: registered = "Could not communicate with the pump. Is it nearby?"
CNL: paired PUMP: unpaired UPLOADER: unregistered = "Could not communicate with the pump. Is it nearby?"
CNL: unpaired PUMP: paired UPLOADER: registered = "Timeout communicating with the Contour Next Link."
CNL: unpaired PUMP: paired UPLOADER: unregistered = "Invalid message received for requestLinkKey, Contour Next Link is not paired with pump."
CNL: unpaired PUMP: unpaired UPLOADER: registered = "Timeout communicating with the Contour Next Link."
CNL: unpaired PUMP: unpaired UPLOADER: unregistered = "Invalid message received for requestLinkKey, Contour Next Link is not paired with pump."

*/

    protected void onHandleIntent(Intent intent) {
//        MedtronicCnlAlarmReceiver.completeWakefulIntent(intent);
//    }

/*
    protected void getMyShit() {
        new Thread() {
            @Override
            public void run() {
                PowerManager.WakeLock wl = getWakeLock("getMyShit", 60000);
                synchronized (lock) {
*/
                    Log.d(TAG, "onHandleIntent called");
                    try {
                        final long timePollStarted = System.currentTimeMillis();
                        long pollInterval = configurationStore.getPollInterval();
                        realm = Realm.getDefaultInstance();

                        long due = checkPollTime();
                        if (due > 0) {
                            statusMessage.add("Please wait: Pump is expecting sensor communication. Poll due in " + ((due - System.currentTimeMillis()) / 1000L) + " seconds");
                            MedtronicCnlAlarmManager.setAlarm(due);
                            return;
                        }

                        // TODO - throw, don't return
                        if (!openUsbDevice()) {
                            Log.d(TAG, "Could not open usb device");

                            MedtronicCnlAlarmManager.setAlarm(System.currentTimeMillis() + 5 * 60 * 1000);
                            statusMessage.add(ICON_WARN + "Could not open usb device");
                            return;
                        }

                        MedtronicCnlReader cnlReader = new MedtronicCnlReader(mHidDevice);

                        realm.beginTransaction();

                        commsActive = true;

                        try {
                            statusMessage.add("Connecting to Contour Next Link");
                            Log.d(TAG, "Connecting to Contour Next Link");
                            cnlReader.requestDeviceInfo();

                            // Is the device already configured?
                            ContourNextLinkInfo info = realm
                                    .where(ContourNextLinkInfo.class)
                                    .equalTo("serialNumber", cnlReader.getStickSerial())
                                    .findFirst();

                            if (info == null) {
                                info = realm.createObject(ContourNextLinkInfo.class, cnlReader.getStickSerial());
                            }

                            cnlReader.getPumpSession().setStickSerial(info.getSerialNumber());

                            cnlReader.enterControlMode();

                            try {
                                cnlReader.enterPassthroughMode();
                                cnlReader.openConnection();

                                cnlReader.requestReadInfo();

                                // always get LinkKey on startup to handle re-paired CNL-PUMP key changes
                                String key = null;
                                if (dataStore.getCommsSuccess() > 0) {
                                    key = info.getKey();
                                }

                                if (key == null) {
                                    cnlReader.requestLinkKey();

                                    info.setKey(MessageUtils.byteArrayToHexString(cnlReader.getPumpSession().getKey()));
                                    key = info.getKey();
                                }

                                cnlReader.getPumpSession().setKey(MessageUtils.hexStringToByteArray(key));

                                long pumpMAC = cnlReader.getPumpSession().getPumpMAC();
                                Log.i(TAG, "PumpInfo MAC: " + (pumpMAC & 0xffffff));
                                PumpInfo activePump = realm
                                        .where(PumpInfo.class)
                                        .equalTo("pumpMac", pumpMAC)
                                        .findFirst();

                                if (activePump == null) {
                                    activePump = realm.createObject(PumpInfo.class, pumpMAC);
                                }

                                activePump.updateLastQueryTS();

                                byte radioChannel = cnlReader.negotiateChannel(activePump.getLastRadioChannel());
                                if (radioChannel == 0) {
                                    statusMessage.add(ICON_WARN + "Could not communicate with the pump. Is it nearby?");
                                    Log.i(TAG, "Could not communicate with the pump. Is it nearby?");
                                    dataStore.incCommsConnectError();
                                    pollInterval = POLL_PERIOD_MS / (configurationStore.isReducePollOnPumpAway() ? 2L : 1L); // reduce polling interval to half until pump is available
                                } else if (cnlReader.getPumpSession().getRadioRSSIpercentage() < 5) {
                                    statusMessage.add(String.format(Locale.getDefault(), "Connected on channel %d  RSSI: %d%%", (int) radioChannel, cnlReader.getPumpSession().getRadioRSSIpercentage()));
                                    statusMessage.add(ICON_WARN + "Warning: pump signal too weak. Is it nearby?");
                                    Log.i(TAG, "Warning: pump signal too weak. Is it nearby?");
                                    dataStore.incCommsConnectError();
                                    dataStore.incCommsSignalError();
                                    pollInterval = POLL_PERIOD_MS / (configurationStore.isReducePollOnPumpAway() ? 2L : 1L); // reduce polling interval to half until pump is available
                                } else {
                                    dataStore.decCommsConnectError();
                                    if (cnlReader.getPumpSession().getRadioRSSIpercentage() < 20)
                                        dataStore.incCommsSignalError();
                                    else
                                        dataStore.decCommsSignalError();

                                    dataStore.setActivePumpMac(pumpMAC);

                                    activePump.setLastRadioChannel(radioChannel);
                                    statusMessage.add(String.format(Locale.getDefault(), "Connected on channel %d  RSSI: %d%%", (int) radioChannel, cnlReader.getPumpSession().getRadioRSSIpercentage()));
                                    Log.d(TAG, String.format("Connected to Contour Next Link on channel %d.", (int) radioChannel));

                                    // read pump status
                                    PumpStatusEvent pumpRecord = realm.createObject(PumpStatusEvent.class);

                                    String deviceName = String.format("medtronic-600://%s", cnlReader.getStickSerial());
                                    activePump.setDeviceName(deviceName);

                                    // TODO - this should not be necessary. We should reverse lookup the device name from PumpInfo
                                    pumpRecord.setDeviceName(deviceName);

                                    if (radioChannel == 26) cnlReader.beginEHSMSession(); // gentle persuasion to leave channel 26 (weakest for CNL and causes Pebble to lose BT often) by using EHSM to influence pump channel change

                                    long pumpTime = cnlReader.getPumpTime().getTime();
                                    pumpOffset = pumpTime - System.currentTimeMillis();
                                    Log.d(TAG, "Time offset between pump and device: " + pumpOffset + " millis.");

                                    pumpRecord.setPumpTimeOffset(pumpOffset);
                                    pumpRecord.setPumpDate(new Date(pumpTime));
                                    pumpRecord.setEventDate(new Date(System.currentTimeMillis()));
                                    cnlReader.updatePumpStatus(pumpRecord);

                                    if (radioChannel == 26) cnlReader.endEHSMSession();

                                    validatePumpRecord(pumpRecord, activePump);
                                    activePump.getPumpHistory().add(pumpRecord);
                                    realm.commitTransaction();

                                    dataStore.incCommsSuccess();
                                    dataStore.clearCommsError();

                                    if (pumpRecord.getBatteryPercentage() <= 25) {
                                        dataStore.incPumpBatteryError();
                                        pollInterval = configurationStore.getLowBatteryPollInterval();
                                    } else {
                                        dataStore.clearPumpBatteryError();
                                    }

                                    if (pumpRecord.isCgmActive()) {
                                        dataStore.clearPumpCgmNA(); // poll clash detection
                                        dataStore.clearPumpLostSensorError();

                                        if (pumpRecord.isCgmWarmUp())
                                            statusMessage.add(ICON_CGM + "sensor is in warm-up phase");
                                        else if (pumpRecord.getCalibrationDueMinutes() == 0)
                                            statusMessage.add(ICON_CGM + "sensor calibration is due now!");
                                        else if (pumpRecord.getSgv() == 0 && pumpRecord.isCgmCalibrating())
                                            statusMessage.add(ICON_CGM + "sensor is calibrating");
                                        else if (pumpRecord.getSgv() == 0)
                                            statusMessage.add(ICON_CGM + "sensor error (pump graph gap)");
                                        else {
                                            dataStore.incCommsSgvSuccess();
                                            // TODO - don't convert SGV here, convert dynamically in ui status message handler
                                            //statusMessage.add("SGV: " + strFormatSGV(pumpRecord.getSgv())
                                            statusMessage.add("SGV: ¦" + pumpRecord.getSgv()
                                                    + "¦  At: " + dateFormatter.format(pumpRecord.getCgmDate().getTime())
                                                    + "  Pump: " + (pumpOffset > 0 ? "+" : "") + (pumpOffset / 1000L) + "sec");
                                            if (pumpRecord.isCgmCalibrating())
                                                statusMessage.add(ICON_CGM + "sensor is calibrating");
                                            if (pumpRecord.isOldSgvWhenNewExpected()) {
                                                statusMessage.add(ICON_CGM + "old SGV event received");
                                                // pump may have missed sensor transmission or be delayed in posting to status message
                                                // in most cases the next scheduled poll will have latest sgv, occasionally it is available this period after a delay
                                                // if user selects double poll option we try again this period or wait until next
                                                pollInterval = configurationStore.isReducePollOnPumpAway() ? 60000 : POLL_PERIOD_MS;
                                            }
                                        }

                                    } else {
                                        dataStore.incPumpCgmNA(); // poll clash detection
                                        if (dataStore.getCommsSgvSuccess() > 0) {
                                            dataStore.incPumpLostSensorError(); // only count errors if cgm is being used
                                            statusMessage.add(ICON_CGM + "cgm n/a (pump lost sensor)");
                                        } else {
                                            statusMessage.add(ICON_CGM + "cgm n/a");
                                        }
                                    }

                                    statusNotifications(pumpRecord);
                                }

                            } catch (UnexpectedMessageException e) {
                                dataStore.incCommsError();
                                pollInterval = 60000L; // retry once during this poll period, this allows for transient radio noise
                                Log.e(TAG, "Unexpected Message", e);
                                statusMessage.add(ICON_WARN + "Communication Error: " + e.getMessage());
                            } catch (TimeoutException e) {
                                dataStore.incCommsError();
                                pollInterval = 90000L; // retry once during this poll period, this allows for transient radio noise
                                Log.e(TAG, "Timeout communicating with the Contour Next Link.", e);
                                statusMessage.add(ICON_WARN + "Timeout communicating with the Contour Next Link / Pump.");
                            } catch (NoSuchAlgorithmException e) {
                                Log.e(TAG, "Could not determine CNL HMAC", e);
                                statusMessage.add(ICON_WARN + "Error connecting to Contour Next Link: Hashing error.");
                            } finally {
                                try {
                                    cnlReader.closeConnection();
                                    cnlReader.endPassthroughMode();
                                    cnlReader.endControlMode();
                                } catch (NoSuchAlgorithmException e) {}
                            }
                        } catch (IOException e) {
                            dataStore.incCommsError();
                            Log.e(TAG, "Error connecting to Contour Next Link.", e);
                            statusMessage.add(ICON_WARN + "Error connecting to Contour Next Link.");
                        } catch (ChecksumException e) {
                            dataStore.incCommsError();
                            Log.e(TAG, "Checksum error getting message from the Contour Next Link.", e);
                            statusMessage.add(ICON_WARN + "Checksum error getting message from the Contour Next Link.");
                        } catch (EncryptionException e) {
                            dataStore.incCommsError();
                            Log.e(TAG, "Error decrypting messages from Contour Next Link.", e);
                            statusMessage.add(ICON_WARN + "Error decrypting messages from Contour Next Link.");
                        } catch (TimeoutException e) {
                            dataStore.incCommsError();
                            Log.e(TAG, "Timeout communicating with the Contour Next Link.", e);
                            statusMessage.add(ICON_WARN + "Timeout communicating with the Contour Next Link.");
                        } catch (UnexpectedMessageException e) {
                            dataStore.incCommsError();
                            Log.e(TAG, "Could not close connection.", e);
                            statusMessage.add(ICON_WARN + "Could not close connection: " + e.getMessage());
                        } finally {
                            commsActive = false;
                            if (realm.isInTransaction()) {
                                // If we didn't commit the transaction, we've run into an error. Let's roll it back
                                realm.cancelTransaction();
                            }

                            if (commsDestroy) {
                                Log.d(TAG, "commsDestroy!!!");
                                statusNotification.endNotification();
                            } else {

                            long nextpoll = requestPollTime(timePollStarted, pollInterval);
                            MedtronicCnlAlarmManager.setAlarm(nextpoll);
                            statusMessage.add("Next poll due at: " + dateFormatter.format(nextpoll));

                            statusNotification.updateNotification(nextpoll);

                            RemoveOutdatedRecords();
                            uploadPollResults();
                            statusWarnings();
                            }
                        }

                    } finally {
                        if (!realm.isClosed()) realm.close();
                        if (mHidDevice != null) {
                            Log.i(TAG, "Closing serial device...");
                            mHidDevice.close();
                            mHidDevice = null;
                        }

            MedtronicCnlAlarmReceiver.completeWakefulIntent(intent);

//                        Intent localIntent = new Intent(MedtronicCnlIntentService.Constants.ACTION_READ_PUMP);
//                        LocalBroadcastManager.getInstance(this).sendBroadcast(localIntent);

//                        releaseWakeLock(wl);
                    }
//                } // lock
//            }
//        }.start();

    }


    private String strFormatSGV(double sgvValue) {

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
        boolean isMmolxl = prefs.getBoolean("mmolxl", false);
        boolean isMmolxlDecimals = prefs.getBoolean("mmolDecimals", false);

        NumberFormat sgvFormatter;
        if (isMmolxl) {
            if (isMmolxlDecimals) {
                sgvFormatter = new DecimalFormat("0.00");
            } else {
                sgvFormatter = new DecimalFormat("0.0");
            }
            return sgvFormatter.format(sgvValue / MMOLXLFACTOR);
        } else {
            sgvFormatter = new DecimalFormat("0");
            return sgvFormatter.format(sgvValue);
        }
    }


    private void statusNotifications(PumpStatusEvent pumpRecord) {
        if (pumpRecord.isValidBGL())
            // TODO - don't convert BGL here, convert dynamically in ui status message handler
            //statusMessage.add(ICON_BGL + "Recent finger BG: ¦" + strFormatSGV(pumpRecord.getRecentBGL()) + "¦");
            statusMessage.add(ICON_BGL + "Recent finger BG: ¦" + pumpRecord.getRecentBGL() + "¦");

        if (pumpRecord.isValidBolus()) {
            if (pumpRecord.isValidBolusSquare())
                statusMessage.add(ICON_BOLUS + "Square bolus delivered: " + pumpRecord.getLastBolusAmount() + "u Started: " + dateFormatter.format(pumpRecord.getLastBolusDate()) + " Duration: " + pumpRecord.getLastBolusDuration() + " minutes");
            else if (pumpRecord.isValidBolusDual())
                statusMessage.add(ICON_BOLUS + "Bolus (dual normal part): " + pumpRecord.getLastBolusAmount() + "u At: " + dateFormatter.format(pumpRecord.getLastBolusDate()));
            else
                statusMessage.add(ICON_BOLUS + "Bolus: " + pumpRecord.getLastBolusAmount() + "u At: " + dateFormatter.format(pumpRecord.getLastBolusDate()));
        }

        if (pumpRecord.isValidTEMPBASAL()) {
            if (pumpRecord.getTempBasalMinutesRemaining() > 0 && pumpRecord.getTempBasalPercentage() > 0)
                statusMessage.add(ICON_BASAL + "Temp basal: " + pumpRecord.getTempBasalPercentage() + "% Remaining: " + pumpRecord.getTempBasalMinutesRemaining() + " minutes");
            else if (pumpRecord.getTempBasalMinutesRemaining() > 0)
                statusMessage.add(ICON_BASAL + "Temp basal: " + pumpRecord.getTempBasalRate() + "u Remaining: " + pumpRecord.getTempBasalMinutesRemaining() + " minutes");
            else
                statusMessage.add(ICON_BASAL + "Temp basal: stopped before expected duration");
        }

        if (pumpRecord.isValidSUSPEND())
            statusMessage.add(ICON_SUSPEND + "Pump suspended insulin delivery approx: " + dateFormatterNote.format(pumpRecord.getSuspendAfterDate()) + " - " + dateFormatterNote.format(pumpRecord.getSuspendBeforeDate()));
        if (pumpRecord.isValidSUSPENDOFF())
            statusMessage.add(ICON_RESUME + "Pump resumed insulin delivery approx: " + dateFormatterNote.format(pumpRecord.getSuspendAfterDate()) + " - " + dateFormatterNote.format(pumpRecord.getSuspendBeforeDate()));

        if (pumpRecord.isValidSAGE())
            statusMessage.add(ICON_CHANGE + "Sensor changed approx: " + dateFormatterNote.format(pumpRecord.getSageAfterDate()) + " - " + dateFormatterNote.format(pumpRecord.getSageBeforeDate()));
        if (pumpRecord.isValidCAGE())
            statusMessage.add(ICON_CHANGE + "Reservoir changed approx: " + dateFormatterNote.format(pumpRecord.getCageAfterDate()) + " - " + dateFormatterNote.format(pumpRecord.getCageBeforeDate()));
        if (pumpRecord.isValidBATTERY())
            statusMessage.add(ICON_CHANGE + "Pump battery changed approx: " + dateFormatterNote.format(pumpRecord.getBatteryAfterDate()) + " - " + dateFormatterNote.format(pumpRecord.getBatteryBeforeDate()));

        if (pumpRecord.isValidALERT())
            statusMessage.add(ICON_BELL + "Active alert on pump At: " + dateFormatter.format(pumpRecord.getAlertDate()));
    }

    private void statusWarnings() {

        if (dataStore.getPumpBatteryError() >= ERROR_PUMPBATTERY_AT) {
            dataStore.clearPumpBatteryError();
            statusMessage.add(ICON_WARN + "Warning: pump battery low");
            if (configurationStore.getLowBatteryPollInterval() != configurationStore.getPollInterval())
                statusMessage.add(ICON_SETTING + "Low battery poll interval: " + (configurationStore.getLowBatteryPollInterval() / 60000) + " minutes");
        }

        if (Math.abs(pumpOffset) > ERROR_PUMPCLOCK_MS)
            dataStore.incPumpClockError();
        if (dataStore.getPumpClockError() >= ERROR_PUMPCLOCK_AT) {
            dataStore.clearPumpClockError();
            statusMessage.add(ICON_WARN + "Warning: Time difference between Pump and Uploader excessive."
                    + " Pump is over " + (Math.abs(pumpOffset) / 60000L) + " minutes " + (pumpOffset > 0 ? "ahead" : "behind") + " of time used by uploader.");
            statusMessage.add(ICON_HELP + "The uploader phone/device should have the current time provided by network. Pump clock drifts forward and needs to be set to correct time occasionally.");
        }

        if (dataStore.getCommsError() >= ERROR_COMMS_AT) {
            statusMessage.add(ICON_WARN + "Warning: multiple comms/timeout errors detected.");
            statusMessage.add(ICON_HELP + "Try: disconnecting and reconnecting the Contour Next Link to phone / restarting phone / check pairing of CNL with Pump.");
        }

        if (dataStore.getPumpLostSensorError() >= ERROR_PUMPLOSTSENSOR_AT) {
            dataStore.clearPumpLostSensorError();
            statusMessage.add(ICON_WARN + "Warning: SGV is unavailable from pump often. The pump is missing transmissions from the sensor.");
            statusMessage.add(ICON_HELP + "Keep pump on same side of body as sensor. Avoid using body sensor locations that can block radio signal.");
        }

        if (dataStore.getCommsConnectError() >= ERROR_CONNECT_AT * (configurationStore.isReducePollOnPumpAway() ? 2 : 1)) {
            dataStore.clearCommsConnectError();
            statusMessage.add(ICON_WARN + "Warning: connecting to pump is failing often.");
            statusMessage.add(ICON_HELP + "Keep pump nearby to uploader phone/device. The body can block radio signals between pump and uploader.");
        }

        if (dataStore.getCommsSignalError() >= ERROR_SIGNAL_AT) {
            dataStore.clearCommsSignalError();
            statusMessage.add(ICON_WARN + "Warning: RSSI radio signal from pump is generally weak and may increase errors.");
            statusMessage.add(ICON_HELP + "Keep pump nearby to uploader phone/device. The body can block radio signals between pump and uploader.");
        }
    }

    private void RemoveOutdatedRecords() {
        final RealmResults<PumpStatusEvent> results =
                realm.where(PumpStatusEvent.class)
                        .lessThan("eventDate", new Date(System.currentTimeMillis() - (48 * 60 * 60 * 1000)))
                        .findAll();

        if (results.size() > 0) {
            realm.executeTransaction(new Realm.Transaction() {
                @Override
                public void execute(Realm realm) {
                    // Delete all matches
                    Log.d(TAG, "Deleting " + results.size() + " records from realm");
                    results.deleteAllFromRealm();
                }
            });
        }
    }

    // TODO - check on optimal use of Realm search+results as we make heavy use for validation

    private void validatePumpRecord(PumpStatusEvent pumpRecord, PumpInfo activePump) {

        int index;
        Date uploaderStartDate = dataStore.getUploaderStartDate();

        // TODO - pump validation is unused but will allow for future record manipulation when adding data from pump history message (gap fill)

        // validate that this contains a new PUMP record
        pumpRecord.setValidPUMP(true);

        // TODO - cgm validation - handle sensor exceptions

        // validate that this contains a new CGM record
        if (pumpRecord.isCgmActive()) {
            pumpRecord.setValidCGM(true);
        }

        // validate that this contains a new SGV record
        if (pumpRecord.isCgmActive()) {
            if (pumpRecord.getPumpDate().getTime() - pumpRecord.getCgmPumpDate().getTime() > POLL_PERIOD_MS + (POLL_GRACE_PERIOD_MS / 2))
                pumpRecord.setOldSgvWhenNewExpected(true);
            else if (!pumpRecord.isCgmWarmUp() && pumpRecord.getSgv() > 0) {
                RealmResults<PumpStatusEvent> sgv_results = activePump.getPumpHistory()
                        .where()
                        .equalTo("cgmPumpDate", pumpRecord.getCgmPumpDate())
                        .equalTo("validSGV", true)
                        .findAll();
                if (sgv_results.size() == 0)
                    pumpRecord.setValidSGV(true);
            }
        }

        // validate that this contains a new BGL record
        if (pumpRecord.getRecentBGL() != 0) {
            RealmResults<PumpStatusEvent> bgl_results = activePump.getPumpHistory()
                    .where()
                    .greaterThan("eventDate", new Date(System.currentTimeMillis() - (20 * 60 * 1000)))
                    .equalTo("recentBGL", pumpRecord.getRecentBGL())
                    .findAll();
            if (bgl_results.size() == 0) {
                pumpRecord.setValidBGL(true);
            }
        }

        // TODO - square/dual stopped and new bolus started between poll snapshots, check realm history to handle this?
        // TODO - multiple bolus between poll snapshots will only have the most recent, check IOB differences to handle this?

        // validate that this contains a new BOLUS record
        RealmResults<PumpStatusEvent> lastbolus_results = activePump.getPumpHistory()
                .where()
                .equalTo("lastBolusPumpDate", pumpRecord.getLastBolusPumpDate())
                .equalTo("lastBolusReference", pumpRecord.getLastBolusReference())
                .equalTo("validBolus", true)
                .findAll();

        if (lastbolus_results.size() == 0) {

            RealmResults<PumpStatusEvent> bolusing_results = activePump.getPumpHistory()
                    .where()
                    .equalTo("bolusingReference", pumpRecord.getLastBolusReference())
                    .greaterThan("bolusingMinutesRemaining", 10)   // if a manual normal bolus referred to here while square is being delivered it will show the remaining time for all bolusing
                    .findAllSorted("eventDate", Sort.ASCENDING);

            if (bolusing_results.size() > 0) {
                long start = pumpRecord.getLastBolusPumpDate().getTime();
                long start_bolusing = bolusing_results.first().getPumpDate().getTime();

                // if pump battery is changed during square/dual bolus period the last bolus time will be set to this time (pump asks user to resume/cancel bolus)
                // use bolusing start time when this is detected
                if (start - start_bolusing > 10 * 60)
                    start = start_bolusing;

                long end = pumpRecord.getPumpDate().getTime();
                long duration = start_bolusing - start + (bolusing_results.first().getBolusingMinutesRemaining() * 60000);
                if (start + duration > end) // was square bolus stopped before expected duration?
                    duration = end - start;

                // check that this was a square bolus and not a normal bolus
                if (duration > 10) {
                    pumpRecord.setValidBolus(true);
                    pumpRecord.setValidBolusSquare(true);
                    pumpRecord.setLastBolusDate(new Date (start));
                    pumpRecord.setLastBolusDuration((short) (duration / 60000));
                }
            }

            else if (pumpRecord.getLastBolusDate().getTime() >= uploaderStartDate.getTime()) {
                pumpRecord.setValidBolus(true);
                if (pumpRecord.getBolusingReference() == pumpRecord.getLastBolusReference()
                        && pumpRecord.getBolusingMinutesRemaining() > 10) {
                    pumpRecord.setValidBolusDual(true);
                }
            }
        }

        // validate that this contains a new TEMP BASAL record
        // temp basal: rate / percentage can be set on pump for max duration of 24 hours / 1440 minutes
        RealmResults<PumpStatusEvent> tempbasal_results = activePump.getPumpHistory()
                .where()
                .greaterThan("eventDate", new Date(System.currentTimeMillis() - (24 * 60 * 60 * 1000)))
                .findAllSorted("eventDate", Sort.DESCENDING);
        if (pumpRecord.getTempBasalMinutesRemaining() > 0) {
            index = 0;
            if (tempbasal_results.size() > 1) {
                short minutes = pumpRecord.getTempBasalMinutesRemaining();
                for (index = 0; index < tempbasal_results.size(); index++) {
                    if (tempbasal_results.get(index).getTempBasalMinutesRemaining() < minutes ||
                            tempbasal_results.get(index).getTempBasalPercentage() != pumpRecord.getTempBasalPercentage() ||
                            tempbasal_results.get(index).getTempBasalRate() != pumpRecord.getTempBasalRate() ||
                            tempbasal_results.get(index).isValidTEMPBASAL())
                        break;
                    minutes = tempbasal_results.get(index).getTempBasalMinutesRemaining();
                }
            }
            if (tempbasal_results.size() > 0)
                if (!tempbasal_results.get(index).isValidTEMPBASAL() ||
                        tempbasal_results.get(index).getTempBasalPercentage() != pumpRecord.getTempBasalPercentage() ||
                        tempbasal_results.get(index).getTempBasalRate() != pumpRecord.getTempBasalRate()) {
                    pumpRecord.setValidTEMPBASAL(true);
                    pumpRecord.setTempBasalAfterDate(tempbasal_results.get(index).getEventDate());
                    pumpRecord.setTempBasalBeforeDate(pumpRecord.getEventDate());
                }
        } else {
            // check if stopped before expected duration
            if (tempbasal_results.size() > 0)
                if (pumpRecord.getPumpDate().getTime() - tempbasal_results.first().getPumpDate().getTime() - (tempbasal_results.first().getTempBasalMinutesRemaining() * 60 * 1000) < -60 * 1000) {
                    pumpRecord.setValidTEMPBASAL(true);
                    pumpRecord.setTempBasalAfterDate(tempbasal_results.first().getEventDate());
                    pumpRecord.setTempBasalBeforeDate(pumpRecord.getEventDate());
                }
        }

        // validate that this contains a new SUSPEND record
        RealmResults<PumpStatusEvent> suspend_results =  activePump.getPumpHistory()
                .where()
                .greaterThan("eventDate", new Date(System.currentTimeMillis() - (2 * 60 * 60 * 1000)))
                .equalTo("validSUSPEND", true)
                .or()
                .equalTo("validSUSPENDOFF", true)
                .findAllSorted("eventDate", Sort.DESCENDING);

        if (suspend_results.size() > 0) {
            // new valid suspend - set temp basal for 0u 60m in NS
            if (pumpRecord.isSuspended() && suspend_results.first().isValidSUSPENDOFF()) {
                pumpRecord.setValidSUSPEND(true);
            }
            // continuation valid suspend every 30m - set temp basal for 0u 60m in NS
            else if (pumpRecord.isSuspended() && suspend_results.first().isValidSUSPEND() &&
                    pumpRecord.getEventDate().getTime() - suspend_results.first().getEventDate().getTime() >= 30 * 60 * 1000) {
                pumpRecord.setValidSUSPEND(true);
            }
            // valid suspendoff - set temp stopped in NS
            else if (!pumpRecord.isSuspended() && suspend_results.first().isValidSUSPEND() &&
                    pumpRecord.getEventDate().getTime() - suspend_results.first().getEventDate().getTime() <= 60 * 60 * 1000) {
                pumpRecord.setValidSUSPENDOFF(true);
                RealmResults<PumpStatusEvent> suspendended_results =  activePump.getPumpHistory()
                        .where()
                        .greaterThan("eventDate", new Date(System.currentTimeMillis() - (2 * 60 * 60 * 1000)))
                        .findAllSorted("eventDate", Sort.DESCENDING);
                pumpRecord.setSuspendAfterDate(suspendended_results.first().getEventDate());
                pumpRecord.setSuspendBeforeDate(pumpRecord.getEventDate());
            }
        }
        else if (pumpRecord.isSuspended()) {
            pumpRecord.setValidSUSPEND(true);
        }

        // absolute suspend start time approx to after-before range
        if (pumpRecord.isValidSUSPEND()) {
            RealmResults<PumpStatusEvent> suspendstarted_results =  activePump.getPumpHistory()
                    .where()
                    .greaterThan("eventDate", new Date(System.currentTimeMillis() - (12 * 60 * 60 * 1000)))
                    .findAllSorted("eventDate", Sort.ASCENDING);
            index = suspendstarted_results.size();
            if (index > 0) {
                while (index > 0) {
                    index--;
                    if (!suspendstarted_results.get(index).isSuspended())
                        break;
                }
                pumpRecord.setSuspendAfterDate(suspendstarted_results.get(index).getEventDate());
            }
            else {
                pumpRecord.setSuspendAfterDate(pumpRecord.getEventDate());
            }
            if (++index < suspendstarted_results.size())
                pumpRecord.setSuspendBeforeDate(suspendstarted_results.get(index).getEventDate());
            else
                pumpRecord.setSuspendBeforeDate(pumpRecord.getEventDate());
        }

        // validate that this contains a new SAGE record
        if (pumpRecord.isCgmWarmUp()) {
            RealmResults<PumpStatusEvent> sage_results = activePump.getPumpHistory()
                    .where()
                    .greaterThan("eventDate", new Date(System.currentTimeMillis() - (130 * 60 * 1000)))
                    .equalTo("validSAGE", true)
                    .findAll();
            if (sage_results.size() == 0) {
                pumpRecord.setValidSAGE(true);
                RealmResults<PumpStatusEvent> sagedate_results = activePump.getPumpHistory()
                        .where()
                        .greaterThan("eventDate", new Date(System.currentTimeMillis() - (6 * 60 * 60 * 1000)))
                        .findAllSorted("eventDate", Sort.DESCENDING);
                pumpRecord.setSageAfterDate(sagedate_results.first().getEventDate());
                pumpRecord.setSageBeforeDate(pumpRecord.getEventDate());
            }
        } else if (pumpRecord.isCgmActive() && pumpRecord.getTransmitterBattery() == 100) {
            // note: transmitter battery can fluctuate when on the edge of a state change
            RealmResults<PumpStatusEvent> sagebattery_results = activePump.getPumpHistory()
                    .where()
                    .greaterThan("eventDate", new Date(System.currentTimeMillis() - (6 * 60 * 60 * 1000)))
                    .equalTo("cgmActive", true)
                    .lessThan("transmitterBattery", 50)
                    .findAllSorted("eventDate", Sort.DESCENDING);
            if (sagebattery_results.size() > 0) {
                RealmResults<PumpStatusEvent> sage_valid_results = activePump.getPumpHistory()
                        .where()
                        .greaterThanOrEqualTo("eventDate", sagebattery_results.first().getEventDate())
                        .equalTo("validSAGE", true)
                        .findAll();
                if (sage_valid_results.size() == 0) {
                    pumpRecord.setValidSAGE(true);
                    pumpRecord.setSageAfterDate(sagebattery_results.first().getEventDate());
                    pumpRecord.setSageBeforeDate(pumpRecord.getEventDate());
                }
            }
        }

        // validate that this contains a new CAGE record
        RealmResults<PumpStatusEvent> cage_results = activePump.getPumpHistory()
                .where()
                .greaterThan("eventDate", new Date(System.currentTimeMillis() - (6 * 60 * 60 * 1000)))
                .lessThan("reservoirAmount", pumpRecord.getReservoirAmount())
                .findAllSorted("eventDate", Sort.DESCENDING);
        if (cage_results.size() > 0) {
            RealmResults<PumpStatusEvent> cage_valid_results = activePump.getPumpHistory()
                    .where()
                    .greaterThanOrEqualTo("eventDate", cage_results.first().getEventDate())
                    .equalTo("validCAGE", true)
                    .findAll();
            if (cage_valid_results.size() == 0) {
                pumpRecord.setValidCAGE(true);
                pumpRecord.setCageAfterDate(cage_results.first().getEventDate());
                pumpRecord.setCageBeforeDate(pumpRecord.getEventDate());
            }
        }

        // validate that this contains a new BATTERY record
        RealmResults<PumpStatusEvent> battery_results = activePump.getPumpHistory()
                .where()
                .greaterThan("eventDate", new Date(System.currentTimeMillis() - (6 * 60 * 60 * 1000)))
                .lessThan("batteryPercentage", pumpRecord.getBatteryPercentage())
                .findAllSorted("eventDate", Sort.DESCENDING);
        if (battery_results.size() > 0) {
            RealmResults<PumpStatusEvent> battery_valid_results = activePump.getPumpHistory()
                    .where()
                    .greaterThanOrEqualTo("eventDate", battery_results.first().getEventDate())
                    .equalTo("validBATTERY", true)
                    .findAll();
            if (battery_valid_results.size() == 0) {
                pumpRecord.setValidBATTERY(true);
                pumpRecord.setBatteryAfterDate(battery_results.first().getEventDate());
                pumpRecord.setBatteryBeforeDate(pumpRecord.getEventDate());
            }
        }

        // validate that this contains a new ALERT record
        if (pumpRecord.getAlert() > 0) {
            RealmResults<PumpStatusEvent> alert_results = activePump.getPumpHistory()
                    .where()
                    .greaterThan("eventDate", new Date(System.currentTimeMillis() - (6 * 60 * 60 * 1000)))
                    .findAllSorted("eventDate", Sort.DESCENDING);
            if (alert_results.size() > 0) {
                if (alert_results.first().getAlert() != pumpRecord.getAlert())
                    pumpRecord.setValidALERT(true);
            }
        }

    }

    // pollInterval: default = POLL_PERIOD_MS (time to pump cgm reading)
    //
    // Can be requested at a shorter or longer interval, used to request a retry before next expected cgm data or to extend poll times due to low pump battery.
    // Requests the next poll based on the actual time last cgm data was available on the pump and adding the interval
    // if this time is already stale then the next actual cgm time will be used
    // Any poll time request that falls within the pre-grace/grace period will be pushed to the next safe time slot

    private long requestPollTime(long lastPoll, long pollInterval) {

        int pumpCgmNA = dataStore.getPumpCgmNA();

        RealmResults<PumpStatusEvent> cgmresults = realm.where(PumpStatusEvent.class)
                .greaterThan("eventDate", new Date(System.currentTimeMillis() - (24 * 60 * 1000)))
                .equalTo("validCGM", true)
                .findAllSorted("cgmDate", Sort.DESCENDING);
        long timeLastCGM = 0;
        if (cgmresults.size() > 0) {
            timeLastCGM = cgmresults.first().getCgmDate().getTime();
        }

        long now = System.currentTimeMillis();
        long lastActualPollTime = lastPoll;
        if (timeLastCGM > 0)
            lastActualPollTime = timeLastCGM + POLL_GRACE_PERIOD_MS + (POLL_PERIOD_MS * ((now - timeLastCGM + POLL_GRACE_PERIOD_MS) / POLL_PERIOD_MS));
        long nextActualPollTime = lastActualPollTime + POLL_PERIOD_MS;
        long nextRequestedPollTime = lastActualPollTime + pollInterval;

        // check if requested poll is stale
        if (nextRequestedPollTime - now < 10 * 1000)
            nextRequestedPollTime = nextActualPollTime;

        // extended unavailable cgm may be due to clash with the current polling time
        // while we wait for a cgm event, polling is auto adjusted by offsetting the next poll based on miss count

        if (timeLastCGM == 0)
            nextRequestedPollTime += 15 * 1000; // push poll time forward to avoid potential clash when no previous poll time available to sync with
        else if (pumpCgmNA >= POLL_ANTI_CLASH)
            nextRequestedPollTime += (((pumpCgmNA - POLL_ANTI_CLASH) % 3) + 1) * 30 * 1000; // adjust poll time in 30 second steps to avoid potential poll clash (adjustment: poll+30s / poll+60s / poll+90s)

        // check if requested poll time is too close to next actual poll time
        if (nextRequestedPollTime > nextActualPollTime - POLL_GRACE_PERIOD_MS - POLL_PRE_GRACE_PERIOD_MS
                && nextRequestedPollTime < nextActualPollTime) {
            nextRequestedPollTime = nextActualPollTime;
        }

        return nextRequestedPollTime;
    }

    private long checkPollTime() {
        long due = 0;

        RealmResults<PumpStatusEvent> cgmresults = realm.where(PumpStatusEvent.class)
                .greaterThan("eventDate", new Date(System.currentTimeMillis() - (24 * 60 * 1000)))
                .equalTo("validCGM", true)
                .findAllSorted("cgmDate", Sort.DESCENDING);

        if (cgmresults.size() > 0) {
            long now = System.currentTimeMillis();
            long timeLastCGM = cgmresults.first().getCgmDate().getTime();
            long timePollExpected = timeLastCGM + POLL_PERIOD_MS + POLL_GRACE_PERIOD_MS + (POLL_PERIOD_MS * ((now - 1000L - (timeLastCGM + POLL_GRACE_PERIOD_MS)) / POLL_PERIOD_MS));
            // avoid polling when too close to sensor-pump comms
            if (((timePollExpected - now) > 5000L) && ((timePollExpected - now) < (POLL_PRE_GRACE_PERIOD_MS + POLL_GRACE_PERIOD_MS)))
                due = timePollExpected;
        }

        return due;
    }

    /**
     * @return if device acquisition was successful
     */
    private boolean openUsbDevice() {
        if (!hasUsbHostFeature()) {
            statusMessage.add(ICON_WARN + "It appears that this device doesn't support USB OTG.");
            Log.e(TAG, "Device does not support USB OTG");
            return false;
        }

        UsbDevice cnlStick = UsbHidDriver.getUsbDevice(mUsbManager, USB_VID, USB_PID);
        if (cnlStick == null) {
            statusMessage.add(ICON_WARN + "USB connection error. Is the Contour Next Link plugged in?");
            Log.w(TAG, "USB connection error. Is the CNL plugged in?");
            return false;
        }

        if (!mUsbManager.hasPermission(UsbHidDriver.getUsbDevice(mUsbManager, USB_VID, USB_PID))) {
            sendMessage(Constants.ACTION_NO_USB_PERMISSION);
            return false;
        }
        mHidDevice = UsbHidDriver.acquire(mUsbManager, cnlStick);

        try {
            mHidDevice.open();
        } catch (Exception e) {
            statusMessage.add(ICON_WARN + "Unable to open USB device");
            Log.e(TAG, "Unable to open serial device", e);
            return false;
        }

        return true;
    }

    // reliable wake alarm manager wake up for all android versions
    public static void wakeUpIntent(Context context, long wakeTime, PendingIntent pendingIntent) {
        final AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            alarm.setExact(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
        } else
            alarm.set(AlarmManager.RTC_WAKEUP, wakeTime, pendingIntent);
    }

    private void uploadPollResults() {
        sendToXDrip();
        uploadToNightscout();
    }

    private void sendToXDrip() {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        if (prefs.getBoolean(getString(R.string.preference_enable_xdrip_plus), false)) {
            final Intent receiverIntent = new Intent(this, XDripPlusUploadReceiver.class);
            final long timestamp = System.currentTimeMillis() + 100L;
//            final long timestamp = System.currentTimeMillis() + 500L;
            final PendingIntent pendingIntent = PendingIntent.getBroadcast(this, (int) timestamp, receiverIntent, PendingIntent.FLAG_ONE_SHOT);
            Log.d(TAG, "Scheduling xDrip+ send");
            wakeUpIntent(getApplicationContext(), timestamp, pendingIntent);
        }
    }

    private void uploadToNightscout() {
        // TODO - set status if offline or Nightscout not reachable
        Intent receiverIntent = new Intent(this, NightscoutUploadReceiver.class);
        final long timestamp = System.currentTimeMillis() + 200L;
//        final long timestamp = System.currentTimeMillis() + 1000L;
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(this, (int) timestamp, receiverIntent, PendingIntent.FLAG_ONE_SHOT);
        wakeUpIntent(getApplicationContext(), timestamp, pendingIntent);
    }

    private boolean hasUsbHostFeature() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_USB_HOST);
    }

    public final class Constants {
        public static final String ACTION_READ_PUMP = "info.nightscout.android.medtronic.READ_PUMP";

        public static final String ACTION_NO_USB_PERMISSION = "info.nightscout.android.medtronic.NO_USB_PERMISSION";
        public static final String ACTION_USB_PERMISSION = "info.nightscout.android.medtronic.USB_PERMISSION";
        public static final String ACTION_USB_REGISTER = "info.nightscout.android.medtronic.USB_REGISTER";

        public static final String ACTION_UPDATE_PUMP = "info.nightscout.android.medtronic.UPDATE_PUMP";
        public static final String ACTION_UPDATE_STATUS = "info.nightscout.android.medtronic.UPDATE_STATUS";

        public static final String EXTENDED_DATA = "info.nightscout.android.medtronic.DATA";
    }


    private class BatteryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context arg0, Intent arg1) {
            if (arg1.getAction().equalsIgnoreCase(Intent.ACTION_BATTERY_LOW)
                    || arg1.getAction().equalsIgnoreCase(Intent.ACTION_BATTERY_CHANGED)
                    || arg1.getAction().equalsIgnoreCase(Intent.ACTION_BATTERY_OKAY)) {
                dataStore.setUploaderBatteryLevel(arg1.getIntExtra(BatteryManager.EXTRA_LEVEL, 0));
            }
        }
    }



    private class UsbReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (MedtronicCnlIntentService.Constants.ACTION_USB_PERMISSION.equals(action)) {
                boolean permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (permissionGranted) {
                    Log.d(TAG, "Got permission to access USB");
                    statusMessage.add(MedtronicCnlIntentService.ICON_INFO + "Got permission to access USB.");
                    startCgmService();
                } else {
                    Log.d(TAG, "Still no permission for USB. Waiting...");
                    waitForUsbPermission();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                Log.d(TAG, "USB plugged in");
//                if (mEnableCgmService) {
                    clearDisconnectionNotification();
//                }
                dataStore.clearAllCommsErrors();
                statusMessage.add(MedtronicCnlIntentService.ICON_INFO + "Contour Next Link plugged in.");
                if (hasUsbPermission()) {
                    // Give the USB a little time to warm up first
                    startCgmServiceDelayed(MedtronicCnlIntentService.USB_WARMUP_TIME_MS);
                } else {
                    Log.d(TAG, "No permission for USB. Waiting.");
                    statusMessage.add(MedtronicCnlIntentService.ICON_INFO + "Waiting for USB permission.");
                    waitForUsbPermission();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                Log.d(TAG, "USB unplugged");
//                if (mEnableCgmService) {
                    showDisconnectionNotification("USB Error", "Contour Next Link unplugged.");
                    statusMessage.add(MedtronicCnlIntentService.ICON_WARN + "USB error. Contour Next Link unplugged.");
//                }
            } else if (MedtronicCnlIntentService.Constants.ACTION_NO_USB_PERMISSION.equals(action)) {
                Log.d(TAG, "No permission to read the USB device.");
                statusMessage.add(MedtronicCnlIntentService.ICON_INFO + "Requesting USB permission.");
                requestUsbPermission();
            }
        }
    }

    private boolean hasUsbPermission() {
        UsbManager usbManager = (UsbManager) this.getSystemService(Context.USB_SERVICE);
        UsbDevice cnlDevice = UsbHidDriver.getUsbDevice(usbManager, MedtronicCnlIntentService.USB_VID, MedtronicCnlIntentService.USB_PID);

        return !(usbManager != null && cnlDevice != null && !usbManager.hasPermission(cnlDevice));
    }

    private void waitForUsbPermission() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent permissionIntent = new Intent(MedtronicCnlIntentService.Constants.ACTION_USB_PERMISSION);
        permissionIntent.putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, hasUsbPermission());
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, permissionIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        alarmManager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000L, pendingIntent);
    }

    private void requestUsbPermission() {
        if (!hasUsbPermission()) {
            UsbManager usbManager = (UsbManager) this.getSystemService(Context.USB_SERVICE);
            UsbDevice cnlDevice = UsbHidDriver.getUsbDevice(usbManager, MedtronicCnlIntentService.USB_VID, MedtronicCnlIntentService.USB_PID);
            PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(MedtronicCnlIntentService.Constants.ACTION_USB_PERMISSION), 0);
            usbManager.requestPermission(cnlDevice, permissionIntent);
        }
    }

    private void showDisconnectionNotification(String title, String message) {
        android.support.v7.app.NotificationCompat.Builder mBuilder =
                (android.support.v7.app.NotificationCompat.Builder) new android.support.v7.app.NotificationCompat.Builder(this)
                        .setPriority(android.support.v7.app.NotificationCompat.PRIORITY_MAX)
                        .setSmallIcon(R.drawable.ic_launcher) // FIXME - this icon doesn't follow the standards (ie, it has black in it)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setTicker(message)
                        .setVibrate(new long[]{1000, 1000, 1000, 1000, 1000});
        // Creates an explicit intent for an Activity in your app
        Intent resultIntent = new Intent(this, MainActivity.class);

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        // Adds the back stack for the Intent (but not the Intent itself)
        stackBuilder.addParentStack(MainActivity.class);
        // Adds the Intent that starts the Activity to the top of the stack
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent =
                stackBuilder.getPendingIntent(
                        0,
                        PendingIntent.FLAG_UPDATE_CURRENT
                );
        mBuilder.setContentIntent(resultPendingIntent);
        NotificationManager mNotificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(USB_DISCONNECT_NOFICATION_ID, mBuilder.build());
    }

    private void clearDisconnectionNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.cancel(USB_DISCONNECT_NOFICATION_ID);
    }

}