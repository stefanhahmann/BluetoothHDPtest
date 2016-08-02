/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.bluetooth.health;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Main user interface for the Sample application.  All Bluetooth health-related
 * operations happen in {@link BluetoothHDPService}.  This activity passes messages to and from
 * the service.
 *
 *    Uses Code found here:
 *    Pulse Oxymeter from Onyx
 *    https://github.com/nunoar/Android-HDP-connection-to-Nonin-Onyx-II-9560/issues/1
 *    https://github.com/nunoar/Android-HDP-connection-to-Nonin-Onyx-II-9560/tree/development
 *
 *    Blood Pressure Device from AnD
 *    https://github.com/andengineering/A-D-HDP-Android-Demo/
 */
public class BluetoothHDPActivity extends Activity {
    private static final String TAG = "BluetoothHealthActivity";

    // Use the appropriate IEEE 11073 data types based on the devices used.
    // Below are some examples.  Refer to relevant Bluetooth HDP specifications for detail.
    // 0x1007 - blood pressure meter
    // 0x1008 - body thermometer
    // 0x100F - body weight scale
    // 0x1004 - pulse oxymeter
    // cf.: https://www.bluetooth.com/specifications/assigned-numbers/health-device-profile

    protected static final int HEALTH_PROFILE_DATA_TYPE_BLOOD_PRESSURE = 0x1007;
    protected static final int HEALTH_PROFILE_DATA_TYPE_OXYMETER = 0x1004;
    protected static final int[] HEALTH_PROFILE_SOURCE_DATA_TYPES = {HEALTH_PROFILE_DATA_TYPE_BLOOD_PRESSURE, HEALTH_PROFILE_DATA_TYPE_OXYMETER};

    private static final int REQUEST_ENABLE_BT = 1;

    private TextView mConnectIndicator;

    private ImageView mBloodPressureDataIndicator;
    private ImageView mOxymeterDataIndicator;
    private final int GREY = 0;
    private final int RED = 1;
    private final int ORANGE = 2;
    private final int LIGHT_GREEN = 3;
    private final int DARK_GREEN = 4;


    private TextView mStatusMessage;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothDevice[] mAllBondedDevices;
    private BluetoothDevice mDevice;
    private int mDeviceIndex = 0;
    private Resources mRes;
    private Messenger mHealthService;
    private boolean mHealthServiceBound;

    // output on device
    private TextView mSys;
    private TextView mDia;
    private TextView mPul;
    private TextView mOxygen;
    private TextView mHeartRate;

    // Handles events sent by {@link HealthHDPService}.
    private Handler mIncomingHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                // Application registration complete.
                case BluetoothHDPService.STATUS_BLOOD_PRESSURE_REG:
                    // mStatusMessage.setText(String.format(mRes.getString(R.string.status_reg), msg.arg1));
                    mBloodPressureDataIndicator.setImageLevel(RED);
                    break;
                // Application registration complete.
                case BluetoothHDPService.STATUS_OXYMETER_REG:
                    // mStatusMessage.setText(String.format(mRes.getString(R.string.status_reg), msg.arg1));
                    mOxymeterDataIndicator.setImageLevel(RED);
                    break;
                // Application unregistration complete.
                case BluetoothHDPService.STATUS_BLOOD_PRESSURE_UNREG:
                    // mStatusMessage.setText(String.format(mRes.getString(R.string.status_unreg), msg.arg1));
                    mBloodPressureDataIndicator.setImageLevel(GREY);
                    break;
                // Application unregistration complete.
                case BluetoothHDPService.STATUS_OXYMETER_UNREG:
                    // mStatusMessage.setText(String.format(mRes.getString(R.string.status_unreg), msg.arg1));
                    mOxymeterDataIndicator.setImageLevel(GREY);
                    break;
                // Reading data from HDP device.
                case BluetoothHDPService.STATUS_BLOOD_PRESSURE_CREATE_CHANNEL:
                    // mStatusMessage.setText(mRes.getString(R.string.read_data));
                    mBloodPressureDataIndicator.setImageLevel(ORANGE);
                    break;
                // Reading data from HDP device.
                case BluetoothHDPService.STATUS_OXYMETER_CREATE_CHANNEL:
                    // mStatusMessage.setText(mRes.getString(R.string.read_data));
                    mOxymeterDataIndicator.setImageLevel(ORANGE);
                    break;
                // Channel destroy complete.  This happens when either the device disconnects or
                // there is extended inactivity.
                case BluetoothHDPService.STATUS_BLOOD_PRESSURE_DESTROY_CHANNEL:
                    // mStatusMessage.setText(mRes.getString(R.string.read_data));
                    mBloodPressureDataIndicator.setImageLevel(RED);
                    break;
                // Channel destroy complete.  This happens when either the device disconnects or
                // there is extended inactivity.
                case BluetoothHDPService.STATUS_OXYMETER_DESTROY_CHANNEL:
                    // mStatusMessage.setText(mRes.getString(R.string.read_data));
                    mOxymeterDataIndicator.setImageLevel(RED);
                    break;
                // Reading data from HDP device.
                case BluetoothHDPService.STATUS_BLOOD_PRESSURE_READ_DATA:
                    // mStatusMessage.setText(mRes.getString(R.string.read_data_done));
                    mBloodPressureDataIndicator.setImageLevel(LIGHT_GREEN);
                    break;
                // Reading data from HDP device.
                case BluetoothHDPService.STATUS_OXYMETER_READ_DATA:
                    // mStatusMessage.setText(mRes.getString(R.string.read_data_done));
                    mOxymeterDataIndicator.setImageLevel(LIGHT_GREEN);
                    break;
                // Finish reading data from HDP device.
                case BluetoothHDPService.STATUS_BLOOD_PRESSURE_READ_DATA_DONE:
                    // mStatusMessage.setText(mRes.getString(R.string.read_data_done));
                    mBloodPressureDataIndicator.setImageLevel(DARK_GREEN);
                    break;
                // Finish reading data from HDP device.
                case BluetoothHDPService.STATUS_OXYMETER_READ_DATA_DONE:
                    // mStatusMessage.setText(mRes.getString(R.string.read_data_done));
                    mOxymeterDataIndicator.setImageLevel(DARK_GREEN);
                    break;
                // Channel creation complete.  Some devices will automatically establish
                // connection.
                /*
                case BluetoothHDPService.STATUS_CREATE_CHANNEL:
                    mStatusMessage.setText(String.format(mRes.getString(R.string.status_create_channel), msg.arg1));
                    if(msg.arg1 > -1) mConnectIndicator.setText(R.string.connected);
                    break;
                // Channel destroy complete.  This happens when either the device disconnects or
                // there is extended inactivity.
                case BluetoothHDPService.STATUS_DESTROY_CHANNEL:
                    mStatusMessage.setText(String.format(mRes.getString(R.string.status_destroy_channel), msg.arg1));
                    mConnectIndicator.setText(R.string.disconnected);
                    break;
                */
                case BluetoothHDPService.RECEIVED_SYS:
                    int sys = msg.arg1;
                    Log.i(TAG, "msg.arg1 @ sys is " + sys);
                    mSys.setText("" + sys);
                    break;
                case BluetoothHDPService.RECEIVED_DIA:
                    int dia = msg.arg1;
                    mDia.setText("" + dia);
                    Log.i(TAG, "msg.arg1 @ dia is " + dia);
                    break;
                case BluetoothHDPService.RECEIVED_PUL:
                    int pul = msg.arg1;
                    Log.i(TAG, "msg.arg1 @ pulse is " + pul);
                    mPul.setText("" + pul);
                    break;
                case BluetoothHDPService.RECEIVED_O2:
                    int oxy = msg.arg1;
                    Log.i(TAG, "msg.arg1 @ o2 is " + oxy);
                    mOxygen.setText("" + msg.arg1);
                    break;
                case BluetoothHDPService.RECEIVED_HEART_RATE:
                    int heart = msg.arg1;
                    Log.i(TAG, "msg.arg1 @ heart is " + heart);
                    mHeartRate.setText("" + msg.arg1);
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    };

    private final Messenger mMessenger = new Messenger(mIncomingHandler);

    // Sets up communication with {@link BluetoothHDPService}.
    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            mHealthServiceBound = true;
            Message msg = Message.obtain(null, BluetoothHDPService.MSG_REG_CLIENT);
            msg.replyTo = mMessenger;
            mHealthService = new Messenger(service);
            Log.i(TAG, "1 onCreate(), mHealthService="+mHealthService);
            try {
                mHealthService.send(msg);
            }
            catch (RemoteException e) {
                Log.w(TAG, "Unable to register client to service.");
                e.printStackTrace();
            }
            for (int i = 0; i < HEALTH_PROFILE_SOURCE_DATA_TYPES.length; i++) {
                sendMessage(BluetoothHDPService.MSG_REG_HEALTH_APP, HEALTH_PROFILE_SOURCE_DATA_TYPES[i]);
            }
        }

        public void onServiceDisconnected(ComponentName name) {
            mHealthService = null;
            mHealthServiceBound = false;
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "onCreate");

        // Check for Bluetooth availability on the Android platform.
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, R.string.bluetooth_not_available, Toast.LENGTH_LONG);
            finish();
            return;
        }
        setContentView(R.layout.console);
        // mConnectIndicator = (TextView) findViewById(R.id.connect_ind);
        // mStatusMessage = (TextView) findViewById(R.id.status_msg);
        mBloodPressureDataIndicator = (ImageView) findViewById(R.id.blood_pressure_indicator);
        mOxymeterDataIndicator = (ImageView) findViewById(R.id.oxymeter_indicator);
        mRes = getResources();
        mHealthServiceBound = false;

        mSys = (TextView) findViewById(R.id.Systolic);
        mDia = (TextView) findViewById(R.id.Diastolic);
        mPul = (TextView) findViewById(R.id.Pulse);
        mOxygen = (TextView) findViewById(R.id.SpO2);
        mHeartRate = (TextView) findViewById(R.id.HeartRate);

        // Initiates application registration through {@link BluetoothHDPService}.
        /*
        Button registerAppButton = (Button) findViewById(R.id.button_register_app);
        registerAppButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sendMessage(BluetoothHDPService.MSG_REG_HEALTH_APP, HEALTH_PROFILE_SOURCE_DATA_TYPE);
            }
        });

        // Initiates application unregistration through {@link BluetoothHDPService}.
        Button unregisterAppButton = (Button) findViewById(R.id.button_unregister_app);
        unregisterAppButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sendMessage(BluetoothHDPService.MSG_UNREG_HEALTH_APP, 0);
            }
        });
        */


        // Initiates channel creation through {@link BluetoothHDPService}.  Some devices will
        // initiate the channel connection, in which case, it is not necessary to do this in the
        // application.  When pressed, the user is asked to select from one of the bonded devices
        // to connect to.
        /*
        Button connectButton = (Button) findViewById(R.id.button_connect_channel);
        connectButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mAllBondedDevices = (BluetoothDevice[]) mBluetoothAdapter.getBondedDevices().toArray(new BluetoothDevice[0]);

                if (mAllBondedDevices.length > 0) {
                    int deviceCount = mAllBondedDevices.length;
                    if (mDeviceIndex < deviceCount) mDevice = mAllBondedDevices[mDeviceIndex];
                    else {
                        mDeviceIndex = 0;
                        mDevice = mAllBondedDevices[0];
                    }
                    String[] deviceNames = new String[deviceCount];
                    int i = 0;
                    for (BluetoothDevice device : mAllBondedDevices) {
                        deviceNames[i++] = device.getName();
                    }
                    SelectDeviceDialogFragment deviceDialog = SelectDeviceDialogFragment.newInstance(deviceNames, mDeviceIndex);
                    deviceDialog.show(getFragmentManager(), "deviceDialog");
                }
            }
        });

        // Initiates channel disconnect through {@link BluetoothHDPService}.
        Button disconnectButton = (Button) findViewById(R.id.button_disconnect_channel);
        disconnectButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                disconnectChannel();
            }
        });
        */

        registerReceiver(mReceiver, initIntentFilter());

    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mHealthServiceBound) unbindService(mConnection);
        unregisterReceiver(mReceiver);
        for (int i = 0; i < HEALTH_PROFILE_SOURCE_DATA_TYPES.length; i++) {
            sendMessage(BluetoothHDPService.MSG_UNREG_HEALTH_APP, HEALTH_PROFILE_SOURCE_DATA_TYPES[i]);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // If Bluetooth is not on, request that it be enabled.
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        }
        else {
            initialize();
        }
    }

    @Override
    protected void onResume() {

        super.onResume();
    }

    @Override
    protected void onPause() {

        super.onPause();
    }

    /**
     * Ensures user has turned on Bluetooth on the Android device.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
        case REQUEST_ENABLE_BT:
            if (resultCode == Activity.RESULT_OK) {
                initialize();
            }
            else {
                finish();
                return;
            }
        }
    }


    /*
    private void connectChannel() {
        sendMessageWithDevice(BluetoothHDPService.MSG_CONNECT_CHANNEL);
    }

    private void disconnectChannel() {
        sendMessageWithDevice(BluetoothHDPService.MSG_DISCONNECT_CHANNEL);
    }
    */


    private void initialize() {
        // Starts health service.
        Intent intent = new Intent(this, BluetoothHDPService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    // Intent filter and broadcast receive to handle Bluetooth on event.
    private IntentFilter initIntentFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        return filter;
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON) {
                    initialize();
                }
            }
        }
    };

    // Sends a message to {@link BluetoothHDPService}.
    private void sendMessage(int what, int value) {
        if (mHealthService == null) {
            Log.i(TAG, "Health Service not connected.");
            return;
        }

        try {
            mHealthService.send(Message.obtain(null, what, value, 0));
        }
        catch (RemoteException e) {
            Log.i(TAG, "Unable to reach service.");
            e.printStackTrace();
        }
    }

    // Sends an update message, along with an HDP BluetoothDevice object, to
    // {@link BluetoothHDPService}.  The BluetoothDevice object is needed by the channel creation
    // method.
    private void sendMessageWithDevice(int what) {
        if (mHealthService == null) {
            Log.d(TAG, "Health Service not connected.");
            return;
        }

        try {
            mHealthService.send(Message.obtain(null, what, mDevice));
        }
        catch (RemoteException e) {
            Log.w(TAG, "Unable to reach service.");
            e.printStackTrace();
        }
    }

    /**
     * Dialog to display a list of bonded Bluetooth devices for user to select from.  This is
     * needed only for channel connection initiated from the application.
     */
    /**
     * Used by {@link SelectDeviceDialogFragment} to record the bonded Bluetooth device selected
     * by the user.
     *
     * @param position Position of the bonded Bluetooth device in the array.
     */
    public void setDevice(int position) {
        mDevice = this.mAllBondedDevices[position];
        mDeviceIndex = position;
    }

    /*
    public static class SelectDeviceDialogFragment extends DialogFragment {

        public static SelectDeviceDialogFragment newInstance(String[] names, int position) {
            SelectDeviceDialogFragment frag = new SelectDeviceDialogFragment();
            Bundle args = new Bundle();
            args.putStringArray("names", names);
            args.putInt("position", position);
            frag.setArguments(args);
            return frag;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            String[] deviceNames = getArguments().getStringArray("names");
            int position = getArguments().getInt("position", -1);
            if (position == -1) position = 0;
            return new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.select_device)
                    .setPositiveButton(R.string.ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                ((BluetoothHDPActivity) getActivity()).connectChannel();
                            }
                        })
                    .setSingleChoiceItems(deviceNames, position,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                ((BluetoothHDPActivity) getActivity()).setDevice(which);
                            }
                        }
                    )
                    .create();
        }
    }
    */

}
