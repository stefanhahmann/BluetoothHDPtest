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

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHealth;
import android.bluetooth.BluetoothHealthAppConfiguration;
import android.bluetooth.BluetoothHealthCallback;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * This Service encapsulates Bluetooth Health API to establish, manage, and disconnect
 * communication between the Android device and a Bluetooth HDP-enabled device. Possible HDP
 * device type includes blood pressure monitor, glucose meter, thermometer, etc.
 *
 * As outlined in the
 * <a href="http://developer.android.com/reference/android/bluetooth/BluetoothHealth.html">BluetoothHealth</a>
 * documentation, the steps involve:
 * 1. Get a reference to the BluetoothHealth proxy object.
 * 2. Create a BluetoothHealth callback and register an application configuration that acts as a
 *    Health SINK.
 * 3. Establish connection to a health device.  Some devices will initiate the connection.  It is
 *    unnecessary to carry out this step for those devices.
 * 4. When connected successfully, read / write to the health device using the file descriptor.
 *    The received data needs to be interpreted using a health manager which implements the
 *    IEEE 11073-xxxxx specifications.
 * 5. When done, close the health channel and unregister the application.  The channel will
 *    also close when there is extended inactivity.
 *
 *    Uses Code found here:
 *    https://github.com/nunoar/Android-HDP-connection-to-Nonin-Onyx-II-9560/issues/1
 *    https://github.com/nunoar/Android-HDP-connection-to-Nonin-Onyx-II-9560/tree/development
 *
 *    Blood Pressure Device from AnD
 *    https://github.com/andengineering/A-D-HDP-Android-Demo/
 */
public class BluetoothHDPService extends Service {
    private static final String TAG = "BluetoothHDPService";

    public static final int RESULT_OK = 0;
    public static final int RESULT_FAIL = -1;

    // Status codes sent back to the UI client.
    // Blood Pressure Application registration complete.
    public static final int STATUS_BLOOD_PRESSURE_REG = 1001;
    // Oxymeter Application registration complete.
    public static final int STATUS_OXYMETER_REG = 1002;
    // Blood Pressure unregistration complete.
    public static final int STATUS_BLOOD_PRESSURE_UNREG = 1011;
    // Oxymeter unregistration complete.
    public static final int STATUS_OXYMETER_UNREG = 1012;
    // Blood Pressure Channel creation complete.
    public static final int STATUS_BLOOD_PRESSURE_CREATE_CHANNEL = 1021;
    // Oxymeter Channel creation complete.
    public static final int STATUS_OXYMETER_CREATE_CHANNEL = 1022;
    // Blood Pressure Channel destroy complete.
    public static final int STATUS_BLOOD_PRESSURE_DESTROY_CHANNEL = 1031;
    // Oxymeter Channel destroy complete.
    public static final int STATUS_OXYMETER_DESTROY_CHANNEL = 1032;
    // Reading data from Bluetooth HDP device.
    public static final int STATUS_BLOOD_PRESSURE_READ_DATA = 1041;
    // Reading data from Bluetooth HDP device.
    public static final int STATUS_OXYMETER_READ_DATA = 1042;
    // Done with reading data.
    public static final int STATUS_BLOOD_PRESSURE_READ_DATA_DONE = 1051;
    // Done with reading data.
    public static final int STATUS_OXYMETER_READ_DATA_DONE = 1052;

    // Message codes received from the UI client.
    // Register client with this service.
    public static final int MSG_REG_CLIENT = 200;
    // Unregister client from this service.
    public static final int MSG_UNREG_CLIENT = 201;
    // Register health application.
    public static final int MSG_REG_HEALTH_APP = 300;
    // Unregister health application.
    public static final int MSG_UNREG_HEALTH_APP = 301;
    // Connect channel.
    // public static final int MSG_CONNECT_CHANNEL = 400;
    // Disconnect channel.
    // public static final int MSG_DISCONNECT_CHANNEL = 401;

    // Got Reading
    public static final int RECEIVED_SYS = 500;
    public static final int RECEIVED_DIA = 501;
    public static final int RECEIVED_PUL = 502;
    public static final int RECEIVED_O2 = 503;
    public static final int RECEIVED_HEART_RATE = 504;

    /**
     * State of communication between Android device and Medical Device
     */
    private int bloodPressureCommunicationState = 0;
    /**
     * State of communication between Android device and Oxymeter
     */
    private int oxymeterCommunicationState = 0;
    private final int ASSOCIATION_RESPONSE = 1;
    private final int GET_MDS = 2;
    private final int DATA_RECEIVED_RESPONSE = 3;
    private final int ASSOCIATION_RELEASE_RESPONSE = 4;

    private List<BluetoothHealthAppConfiguration> mHealthAppConfig;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothHealth mBluetoothHealth;

    // private BluetoothDevice mDevice;
    // private int mChannelId;

    private Messenger mClient;
    byte invoke[] = new byte[]{(byte) 0x00, (byte) 0x00};

    // Handles events sent by {@link HealthHDPActivity}.
    private class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                // Register UI client to this service so the client can receive messages.
                case MSG_REG_CLIENT:
                    Log.d(TAG, "Activity client registered");
                    mClient = msg.replyTo;
                    break;
                // Unregister UI client from this service.
                case MSG_UNREG_CLIENT:
                    mClient = null;
                    break;
                // Register health application.
                case MSG_REG_HEALTH_APP:
                    registerApp(msg.arg1);
                    break;
                // Unregister health application.
                case MSG_UNREG_HEALTH_APP:
                    unregisterApp();
                    break;
                /*
                // Connect channel.
                case MSG_CONNECT_CHANNEL:
                    mDevice = (BluetoothDevice) msg.obj;
                    Log.i(TAG, "3 mDevice="+mDevice);
                    connectChannel();
                    break;
                // Disconnect channel.
                case MSG_DISCONNECT_CHANNEL:
                    mDevice = (BluetoothDevice) msg.obj;
                    disconnectChannel();
                    break;
                */
                default:
                    super.handleMessage(msg);
            }
        }
    }

    final Messenger mMessenger = new Messenger(new IncomingHandler());

    /**
     * Make sure Bluetooth and health profile are available on the Android device.  Stop service
     * if they are not available.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            // Bluetooth adapter isn't available.  The client of the service is supposed to
            // verify that it is available and activate before invoking this service.
            stopSelf();
            return;
        }
        if (!mBluetoothAdapter.getProfileProxy(this, mBluetoothServiceListener, BluetoothProfile.HEALTH)) {
            Toast.makeText(this, R.string.bluetooth_health_profile_not_available, Toast.LENGTH_LONG);
            stopSelf();
            return;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "BluetoothHDPService is running.");
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    };

    // Register health application through the Bluetooth Health API.
    private void registerApp(int dataType) {
        mBluetoothHealth.registerSinkAppConfiguration(TAG, dataType, mHealthCallback);
    }

    // Unregister health application through the Bluetooth Health API.
    private void unregisterApp() {
        for (int i = 0; i < mHealthAppConfig.size(); i++) {
            mBluetoothHealth.unregisterAppConfiguration(mHealthAppConfig.get(i));
        }
    }

    /*
    // Connect channel through the Bluetooth Health API.
    private void connectChannel() {
        Log.i(TAG, "connectChannel(), mDevice="+mDevice+", mHealthAppConfig="+mHealthAppConfig);
        boolean connectChannelToSource = mBluetoothHealth.connectChannelToSource(mDevice, mHealthAppConfig);
        Log.i(TAG, "connectChannel(), connectChannelToSource="+connectChannelToSource);
    }

    // Disconnect channel through the Bluetooth Health API.
    private void disconnectChannel() {
        Log.i(TAG, "disconnectChannel(), mDevice="+mDevice+", mHealthAppConfig="+mHealthAppConfig+", mChannelId="+mChannelId);
        mBluetoothHealth.disconnectChannel(mDevice, mHealthAppConfig, mChannelId);
    }
    */

    // Callbacks to handle connection set up and disconnection clean up.
    private final BluetoothProfile.ServiceListener mBluetoothServiceListener = new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HEALTH) {
                mBluetoothHealth = (BluetoothHealth) proxy;
                if (Log.isLoggable(TAG, Log.DEBUG))
                    Log.d(TAG, "onServiceConnected to profile: " + profile);
            }
        }

        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HEALTH) {
                mBluetoothHealth = null;
            }
        }
    };

    private final BluetoothHealthCallback mHealthCallback = new BluetoothHealthCallback() {
        // Callback to handle application registration and unregistration events.  The service
        // passes the status back to the UI client.
        public void onHealthAppConfigurationStatusChange(BluetoothHealthAppConfiguration config, int status) {
            if (status == BluetoothHealth.APP_CONFIG_REGISTRATION_FAILURE) {
                Log.i(TAG, "Health App configuration (" + config + ") has NOT been REGISTERED");
                if (config != null) {
                    // TODO: should turn data indicator from any to grey
                    if (config.getDataType() == BluetoothHDPActivity.HEALTH_PROFILE_DATA_TYPE_BLOOD_PRESSURE) {
                        sendMessage(STATUS_BLOOD_PRESSURE_REG, RESULT_FAIL);
                    }
                    if (config.getDataType() == BluetoothHDPActivity.HEALTH_PROFILE_DATA_TYPE_OXYMETER) {
                        sendMessage(STATUS_OXYMETER_REG, RESULT_FAIL);
                    }
                }
            }
            else if (status == BluetoothHealth.APP_CONFIG_REGISTRATION_SUCCESS) {
                Log.i(TAG, "Health App configuration ("+config+") has successfully been REGISTERED!");
                if (config != null) {
                    // TODO: should turn data indicator from grey to red
                    if (config.getDataType() == BluetoothHDPActivity.HEALTH_PROFILE_DATA_TYPE_BLOOD_PRESSURE) {
                        sendMessage(STATUS_BLOOD_PRESSURE_REG, RESULT_OK);
                    }
                    if (config.getDataType() == BluetoothHDPActivity.HEALTH_PROFILE_DATA_TYPE_OXYMETER) {
                        sendMessage(STATUS_OXYMETER_REG, RESULT_OK);
                    }
                }
                if (mHealthAppConfig == null) {
                    mHealthAppConfig = new ArrayList<BluetoothHealthAppConfiguration>();
                }
                mHealthAppConfig.add(config);
            }
            else if (status == BluetoothHealth.APP_CONFIG_UNREGISTRATION_FAILURE || status == BluetoothHealth.APP_CONFIG_UNREGISTRATION_SUCCESS) {
                Log.i(TAG, "Health App configuration ("+config+") has successfully been UNREGISTERED!");
                if (config != null) {
                    // TODO: should turn data indicator from any to grey
                    if (config.getDataType() == BluetoothHDPActivity.HEALTH_PROFILE_DATA_TYPE_BLOOD_PRESSURE) {
                        sendMessage(STATUS_BLOOD_PRESSURE_UNREG, status == BluetoothHealth.APP_CONFIG_UNREGISTRATION_SUCCESS ? RESULT_OK : RESULT_FAIL);
                    }
                    if (config.getDataType() == BluetoothHDPActivity.HEALTH_PROFILE_DATA_TYPE_OXYMETER) {
                        sendMessage(STATUS_OXYMETER_REG, status == BluetoothHealth.APP_CONFIG_UNREGISTRATION_SUCCESS ? RESULT_OK : RESULT_FAIL);
                    }
                }
                if (mHealthAppConfig != null) {
                    for (int i = 0; i < mHealthAppConfig.size(); i++) {
                        if (mHealthAppConfig.get(i).equals(config)) {
                            mHealthAppConfig.remove(i);
                        }
                    }
                }
            }
        }

        // Callback to handle channel connection state changes.
        // Note that the logic of the state machine may need to be modified based on the HDP device.
        // When the HDP device is connected, the received file descriptor is passed to the
        // OxymeterReadThread to read the content.
        public void onHealthChannelStateChange(BluetoothHealthAppConfiguration config, BluetoothDevice device, int prevState, int newState, ParcelFileDescriptor fd, int channelId) {
            Log.i(TAG, "onHealthChannelStateChange()");
            Log.i(TAG, String.format("prevState="+prevState+", newState="+newState));
            if (prevState == BluetoothHealth.STATE_CHANNEL_DISCONNECTED && newState == BluetoothHealth.STATE_CHANNEL_CONNECTING) {
                Log.i(TAG, "CONNECTING: config="+config+", device="+device);
            }
            if ((prevState == BluetoothHealth.STATE_CHANNEL_DISCONNECTED || prevState == BluetoothHealth.STATE_CHANNEL_CONNECTING) && newState == BluetoothHealth.STATE_CHANNEL_CONNECTED) {
                Log.i(TAG, "CONNECTED: config="+config+", device="+device);
                for (int i = 0; i < mHealthAppConfig.size(); i++) {
                    if (config.equals(mHealthAppConfig.get(i))) {
                        // Log.i(TAG, "channelId="+channelId);
                        if(config.getDataType() == BluetoothHDPActivity.HEALTH_PROFILE_DATA_TYPE_BLOOD_PRESSURE) {
                            sendMessage(STATUS_BLOOD_PRESSURE_CREATE_CHANNEL, RESULT_OK);
                            sendMessage(RECEIVED_SYS, -1);
                            sendMessage(RECEIVED_DIA, -1);
                            sendMessage(RECEIVED_PUL, -1);
                            (new BloodPressureReadThread((fd))).start();
                        }
                        else if(config.getDataType() == BluetoothHDPActivity.HEALTH_PROFILE_DATA_TYPE_OXYMETER) {
                            sendMessage(STATUS_OXYMETER_CREATE_CHANNEL, RESULT_OK);
                            sendMessage(RECEIVED_O2, -1);
                            sendMessage(RECEIVED_HEART_RATE, -1);
                            (new OxymeterReadThread(fd)).start();
                        }
                    }
                }
            }
            else if (prevState == BluetoothHealth.STATE_CHANNEL_CONNECTING && newState == BluetoothHealth.STATE_CHANNEL_DISCONNECTED) {
                Log.i(TAG, "DISCONNECTING: config="+config+", device="+device);
                if(config.getDataType() == BluetoothHDPActivity.HEALTH_PROFILE_DATA_TYPE_BLOOD_PRESSURE) {
                    sendMessage(STATUS_BLOOD_PRESSURE_CREATE_CHANNEL, RESULT_FAIL);
                }
                else if(config.getDataType() == BluetoothHDPActivity.HEALTH_PROFILE_DATA_TYPE_OXYMETER) {
                    sendMessage(STATUS_OXYMETER_CREATE_CHANNEL, RESULT_FAIL);
                }
            }
            else if (newState == BluetoothHealth.STATE_CHANNEL_DISCONNECTED) {
                Log.i(TAG, "DISCONNECTED: config="+config+", device="+device);
                if(config.getDataType() == BluetoothHDPActivity.HEALTH_PROFILE_DATA_TYPE_BLOOD_PRESSURE) {
                    sendMessage(STATUS_BLOOD_PRESSURE_DESTROY_CHANNEL, RESULT_OK);
                }
                else if(config.getDataType() == BluetoothHDPActivity.HEALTH_PROFILE_DATA_TYPE_OXYMETER) {
                    sendMessage(STATUS_OXYMETER_DESTROY_CHANNEL, RESULT_OK);
                }
            }
        }
    };

    // Sends an update message to registered UI client.
    private void sendMessage(int what, int value) {
        Log.i(TAG, "what="+what+", value="+value);
        if (mClient == null) {
            Log.i(TAG, "No clients registered.");
            return;
        }

        try {
            mClient.send(Message.obtain(null, what, value, 0));
        }
        catch (RemoteException e) {
            // Unable to reach client.
            e.printStackTrace();
        }
    }

    public String byte2hex(byte[] b)
    {
        // String Buffer can be used instead
        String hs = "";
        String stmp = "";

        for (int n = 0; n < b.length; n++) {
            stmp = (java.lang.Integer.toHexString(b[n] & 0XFF));

            if (stmp.length() == 1) {
                hs = hs + "0" + stmp;
            }
            else {
                hs = hs + stmp;
            }

            if (n < b.length - 1) {
                hs = hs + "";
            }
        }

        return hs;
    }

    public static int byteToUnsignedInt(byte b) {
        return 0x00 << 24 | b & 0xff;
    }

    // Thread to read incoming data received from the HDP device.  This sample application merely
    // reads the raw byte from the incoming file descriptor.  The data should be interpreted using
    // a health manager which implements the IEEE 11073-xxxxx specifications.
    private class BloodPressureReadThread extends Thread {
        private ParcelFileDescriptor mFd;

        public BloodPressureReadThread(ParcelFileDescriptor fd) {
            super();
            mFd = fd;
        }

        @Override
        public void run() {
            FileInputStream fis = new FileInputStream(mFd.getFileDescriptor());
            byte data[] = new byte[300];
            try {
                while(fis.read(data) > -1) {
                    // At this point, the application can pass the raw data to a parser that
                    // has implemented the IEEE 11073-xxxxx specifications.  Instead, this sample
                    // simply indicates that some data has been received.
                    Log.i(TAG, "data="+byte2hex(data));
                    if (data[0] != (byte) 0x00) {
                        if(data[0] == (byte) 0xE2) {
                            Log.i(TAG, "E2");
                            //data_AR
                            bloodPressureCommunicationState = ASSOCIATION_RESPONSE;
                            (new BloodPressureWriteThread(mFd)).start();
                            try {
                                sleep(100);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            //get_MDS (i.e. get Medical Device System)
                            bloodPressureCommunicationState = GET_MDS;
                            (new BloodPressureWriteThread(mFd)).start();
                        }
                        else if (data[0] == (byte)0xE7){
                            Log.i(TAG, "E7");

                            //work for legacy device...
                            if (data[18] == (byte) 0x0d && data[19] == (byte) 0x1d)  // fixed report, cf. p. 40 (chapter E.5.1) of ISO/IEEE 11073-10407:2010(E) (http://ieeexplore.ieee.org/stamp/stamp.jsp?tp=&arnumber=5682320):
                            {
                                bloodPressureCommunicationState = DATA_RECEIVED_RESPONSE;
                                //set invoke id so get correct response
                                invoke = new byte[] { data[6], data[7]};
                                //write back response
                                (new BloodPressureWriteThread(mFd)).start();
                                //parse data!!
                                int length = data[21];
                                Log.i(TAG, "length is " + length);
                                // check data-req-id
                                int report_no = data[22+3];
                                int number_of_data_packets = data[22+5];
                                Log.i(TAG, "number_of_data_packets="+number_of_data_packets+", hex was:"+String.format("%02X ", data[22+5]));
                                // packet_start starts from handle 0 byte
                                int packet_start = 30;
                                final int SYS_DIA_MAP_DATA = 1;
                                final int PULSE_DATA = 2;
                                final int ERROR_CODE_DATA = 3;
                                for (int i = 0; i < number_of_data_packets; i++)
                                {
                                    int obj_handle = data[packet_start+1];
                                    Log.i(TAG, "obj_handle="+obj_handle+" hex was:"+String.format("%02X ", obj_handle));
                                    switch (obj_handle)
                                    {
                                        case SYS_DIA_MAP_DATA:
                                            int sys = byteToUnsignedInt(data[packet_start+9]);
                                            int dia = byteToUnsignedInt(data[packet_start+11]);
                                            int map = byteToUnsignedInt(data[packet_start+13]);
                                            //create team string... 9+13~9+20
                                            Log.i(TAG, "1 sys is "+ sys + ", hex was:"+String.format("%02X ", data[packet_start+9]));
                                            sendMessage(RECEIVED_SYS, sys);
                                            Log.i(TAG, "2 dia is "+ dia + ", hex was:"+String.format("%02X ", data[packet_start+11]));
                                            sendMessage(RECEIVED_DIA, dia);
                                            Log.i(TAG, "3 map is "+ map + ", hex was:"+String.format("%02X ", data[packet_start+13]));
                                            //test
                                            // sendMessage(RECEIVED_MAP, map);
                                            break;
                                        case PULSE_DATA:
                                            //parse
                                            int pulse = byteToUnsignedInt(data[packet_start+5]);
                                            Log.i(TAG, "4 pulse is " + pulse + ", hex was:"+String.format("%02X ", data[packet_start+5]));
                                            sendMessage(RECEIVED_PUL, pulse);
                                            break;
                                        case ERROR_CODE_DATA:
                                            //need more signal
                                            break;
                                    }
                                    packet_start += 4 + data[packet_start+3];	//4 = ignore beginning four bytes
                                    Log.i(TAG, "increment=" + (4 + data[packet_start+3])+", hex was:"+String.format("%02X ", data[packet_start+3]));
                                }
                            }
                            else
                            {
                                bloodPressureCommunicationState = GET_MDS;
                            }
                        }
                        else if (data[0] == (byte) 0xE4)
                        {
                            bloodPressureCommunicationState = ASSOCIATION_RELEASE_RESPONSE;
                            (new BloodPressureWriteThread(mFd)).start();
//	                		sendMessage();
                        }
                        //zero out the data
                        for (int i = 0; i < data.length; i++){
                            data[i] = (byte) 0x00;
                        }
                    }
                    sendMessage(STATUS_BLOOD_PRESSURE_READ_DATA, 0);
                }
            }
            catch(IOException ioe) {

            }
            if (mFd != null) {
                try {
                    mFd.close();
                } catch (IOException e) {
                    // Do nothing.
                }
            }
            sendMessage(STATUS_BLOOD_PRESSURE_READ_DATA_DONE, 0);
        }
    }


    private class BloodPressureWriteThread extends Thread {
        private ParcelFileDescriptor mFd;

        public BloodPressureWriteThread(ParcelFileDescriptor fd) {
            super();
            mFd = fd;
        }

        @Override
        public void run() {
            FileOutputStream fos = new FileOutputStream(mFd.getFileDescriptor());
            // Association Response (AR) [0xE300] from Smartphone to Association Request from Medical Device
            // cf. p. 35 of ISO/IEEE 11073-10407:2010(E) (http://ieeexplore.ieee.org/stamp/stamp.jsp?tp=&arnumber=5682320):
            //
            // "A manager (e.g. the Android device) responds to the agent that it can associate with, recognizes, and accepts and has the blood
            // pressure monitor’s extended configuration (i.e., there is no need for the agent to send its configuration)."
            final byte AR[] = new byte[] {			    (byte) 0xE3, (byte) 0x00,
                    (byte) 0x00, (byte) 0x2C,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x50, (byte) 0x79,
                    (byte) 0x00, (byte) 0x26,
                    (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x80, (byte) 0x00,
                    (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x08,  //bt add for phone, can be automate in the future
                    (byte) 0x3C, (byte) 0x5A, (byte) 0x37, (byte) 0xFF,
                    (byte) 0xFE, (byte) 0x95, (byte) 0xEE, (byte) 0xE3,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00};

            // The GET MDS (Medical Device System) Attributes method is invoked at any time, when a device is in Associated state (i.e. Association Response has been sent)
            // GET MDS method, cf. p. 39 of ISO/IEEE 11073-10407:2010(E) (http://ieeexplore.ieee.org/stamp/stamp.jsp?tp=&arnumber=5682320):
            //
            // "Get all medical device system attributes request. The manager (e.g. the Android device) queries the agent for its MDS object attributes
            final byte get_MDS[] = new byte[] { 	    (byte) 0xE7, (byte) 0x00,
                    (byte) 0x00, (byte) 0x0E,
                    (byte) 0x00, (byte) 0x0C,
                    (byte) 0x00, (byte) 0x24,
                    (byte) 0x01, (byte) 0x03,
                    (byte) 0x00, (byte) 0x06,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00 };

            // Data Received Response
            // cf. p. 41 of ISO/IEEE 11073-10407:2010(E) (http://ieeexplore.ieee.org/stamp/stamp.jsp?tp=&arnumber=5682320):
            //
            // "The manager (e.g. the Android Device) confirms receipt of the agent's event report"
            final byte DRR[] = new byte[] { 		    (byte) 0xE7, (byte) 0x00,
                    (byte) 0x00, (byte) 0x12,
                    (byte) 0x00, (byte) 0x10,
                    (byte) invoke[0], (byte) invoke[1],
                    (byte) 0x02, (byte) 0x01,
                    (byte) 0x00, (byte) 0x0A,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x0D, (byte) 0x1D,
                    (byte) 0x00, (byte) 0x00 };

            // Association Release Response (ARR)
            // cf. p. 41 of ISO/IEEE 11073-10407:2010(E) (http://ieeexplore.ieee.org/stamp/stamp.jsp?tp=&arnumber=5682320):
            //
            // "A manager (i.e. the Android Device) responds to the agent (i.e. the medical device) that it can release association"
            final byte ARR[] = new byte[] {		        (byte) 0xE5, (byte) 0x00,
                    (byte) 0x00, (byte) 0x02,
                    (byte) 0x00, (byte) 0x00 };

            try {
                Log.i(TAG, String.valueOf(bloodPressureCommunicationState));
                if (bloodPressureCommunicationState == ASSOCIATION_RESPONSE)
                {
                    fos.write(AR);
                    Log.i(TAG, "Blood Pressure Association Responsed!");
                }
                else if (bloodPressureCommunicationState == GET_MDS)
                {
                    fos.write(get_MDS);
                    Log.i(TAG, "Blood Pressure Get MDS object attributes!");
                }
                else if (bloodPressureCommunicationState == DATA_RECEIVED_RESPONSE)
                {
                    fos.write(DRR);
                    Log.i(TAG, "Blood Pressure Data Responsed!");
                }
                else if (bloodPressureCommunicationState == ASSOCIATION_RELEASE_RESPONSE)
                {
                    fos.write(ARR);
                    Log.i(TAG, "Blood Pressure Association Released!");
                }
            } catch(IOException ioe) {}
        }
    }

    // Thread to read incoming data received from the HDP device.  This sample application merely
    // reads the raw byte from the incoming file descriptor.  The data should be interpreted using
    // a health manager which implements the IEEE 11073-xxxxx specifications.
    private class OxymeterReadThread extends Thread {
        private ParcelFileDescriptor mFd;

        public OxymeterReadThread(ParcelFileDescriptor fd) {
            super();
            mFd = fd;
        }

        @Override
        public void run() {
            FileInputStream fis = new FileInputStream(mFd.getFileDescriptor());
            byte data[] = new byte[116];
            try {
                while (fis.read(data) > -1) {
                    // At this point, the application can pass the raw data to a parser that
                    // has implemented the IEEE 11073-xxxxx specifications.  Instead, this sample
                    // simply indicates that some data has been received.

                    if (data[0] != (byte) 0x00) {

                        if (data[0] == (byte) 0xE2) {
                            //Log.i(TAG, "E2 - Association Request");
                            oxymeterCommunicationState = ASSOCIATION_RESPONSE;

                            (new OxymeterWriteThread(mFd)).start();
                            try {
                                sleep(100);
                            }
                            catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                            oxymeterCommunicationState = GET_MDS;
                            (new OxymeterWriteThread(mFd)).start();
                        }
                        else if (data[0] == (byte) 0xE7) {
                            Log.i(TAG, "E7 - Data Given");

                            if (data[3] != (byte) 0xda) {

                                invoke[0] = data[6];
                                invoke[1] = data[7];

                                if (data[3] == (byte) 0x36) {
                                    int oxygen = byteToUnsignedInt(data[35]);
                                    int heartRate = byteToUnsignedInt(data[49]);

                                    Log.i("oxygen", "" + oxygen);
                                    Log.i("heartRate", "" + heartRate);
                                    sendMessage(RECEIVED_O2, oxygen);
                                    sendMessage(RECEIVED_HEART_RATE, heartRate);
                                }

                                oxymeterCommunicationState = DATA_RECEIVED_RESPONSE;
                                //set invoke id so get correct response
                                (new OxymeterWriteThread(mFd)).start();
                            }
                            //parse data!!
                        }
                        else if (data[0] == (byte) 0xE4) {
                            oxymeterCommunicationState = ASSOCIATION_RELEASE_RESPONSE;
                            (new OxymeterWriteThread(mFd)).start();
                            //sendMessage();

                        }

                        //zero out the data
                        Arrays.fill(data, (byte) 0x00);
                    }
                    sendMessage(STATUS_OXYMETER_READ_DATA, 0);
                }
            } catch (IOException ioe) {
                /* Do nothing. */
            }
            if (mFd != null) {
                try {
                    mFd.close();
                }
                catch (IOException e) {
                    /* Do nothing. */
                }
            }
            sendMessage(STATUS_OXYMETER_READ_DATA_DONE, 0);
        }
    }

    private class OxymeterWriteThread extends Thread {
        private ParcelFileDescriptor mFd;

        public OxymeterWriteThread(ParcelFileDescriptor fd) {
            super();
            mFd = fd;
        }

        public byte[] getBluetoothMacAddress() {
            BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

            // if device does not support Bluetooth
            if (mBluetoothAdapter == null) {
                Log.d(TAG, "device does not support bluetooth");
                return null;
            }

            String[] mac = mBluetoothAdapter.getAddress().split(":");
            byte[] macAddress = new byte[mac.length];

            for (int i = 0; i < mac.length; i++) {
                Integer hex = Integer.parseInt(mac[i], 16);
                macAddress[i] = hex.byteValue();
            }

            return macAddress;
        }

        @Override
        public void run() {
            FileOutputStream fos = new FileOutputStream(mFd.getFileDescriptor());

            byte[] macAddress = getBluetoothMacAddress();

            // Association Response (AR) [0xE300] from Smartphone to Association Request from Medical Device
            // cf. pp. 59 of ISO/IEEE 11073-10404:2010(E) (http://ieeexplore.ieee.org/stamp/stamp.jsp?tp=&arnumber=6235069):
            //
            // "A manager (e.g. the Android device) responds to the agent that it can associate with, recognizes, and accepts and has the blood
            // pressure monitor’s extended configuration (i.e., there is no need for the agent to send its configuration)."
            final byte AR[] = new byte[]{
                    (byte) 0xE3, (byte) 0x00,
                    (byte) 0x00, (byte) 0x2C,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x50, (byte) 0x79,
                    (byte) 0x00, (byte) 0x26,
                    (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x80, (byte) 0x00,
                    (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x80, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x08,  //bt add for phone, can be automate in the future
                    macAddress[0], macAddress[1], macAddress[2], (byte) 0xFF,
                    (byte) 0xFE,   macAddress[3], macAddress[4], macAddress[5],
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00
            };

            // The GET MDS (Medical Device System) Attributes method is invoked at any time, when a device is in Associated state (i.e. Association Response has been sent)
            // cf. p. 65 of ISO/IEEE 11073-10404:2010(E) (http://ieeexplore.ieee.org/stamp/stamp.jsp?tp=&arnumber=6235069):
            //
            // "Get all medical device system attributes request. The manager (e.g. the Android device) queries the agent for its MDS object attributes
            final byte get_MDS[] = new byte[]{
                    (byte) 0xE7, (byte) 0x00,
                    (byte) 0x00, (byte) 0x0E,
                    (byte) 0x00, (byte) 0x0C,
                    (byte) 0x12, (byte) 0x34,
                    (byte) 0x01, (byte) 0x03,
                    (byte) 0x00, (byte) 0x06,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00
            };

            // Data Received Response
            // cf. p. 67 of ISO/IEEE 11073-10404:2010(E) (http://ieeexplore.ieee.org/stamp/stamp.jsp?tp=&arnumber=6235069):
            //
            // "The manager (e.g. the Android Device) confirms receipt of the agent's event report"
            final byte DRR[] = new byte[]{
                    (byte) 0xE7, (byte) 0x00,
                    (byte) 0x00, (byte) 0x12,
                    (byte) 0x00, (byte) 0x10,
                    (byte) invoke[0], (byte) invoke[1],
                    (byte) 0x02, (byte) 0x01,
                    (byte) 0x00, (byte) 0x0A,
                    (byte) 0x00, (byte) 0x00,
                    (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                    (byte) 0x0D, (byte) 0x1D,
                    (byte) 0x00, (byte) 0x00
            };


            // Association Release Response (ARR)
            // cf. p. 69 of ISO/IEEE 11073-10404:2010(E) (http://ieeexplore.ieee.org/stamp/stamp.jsp?tp=&arnumber=6235069):
            //
            // "A manager (i.e. the Android Device) responds to the agent (i.e. the medical device) that it can release association"
            final byte ARR[] = new byte[]{
                    (byte) 0xE5, (byte) 0x00,
                    (byte) 0x00, (byte) 0x02,
                    (byte) 0x00, (byte) 0x00
            };

            try {
                if (oxymeterCommunicationState == ASSOCIATION_RESPONSE) {
                    fos.write(AR);
                    Log.i(TAG, "Oxymeter Association Responsed!");
                }
                else if (oxymeterCommunicationState == GET_MDS) {
                    fos.write(get_MDS);
                    Log.i(TAG, "Oxymeter Get MDS object attributes!");
                }
                else if (oxymeterCommunicationState == DATA_RECEIVED_RESPONSE) {
                    fos.write(DRR);
                    Log.i(TAG, "Oxymeter Data Responsed!");
                }
                else if (oxymeterCommunicationState == ASSOCIATION_RELEASE_RESPONSE) {
                    fos.write(ARR);
                    Log.i(TAG, "Oxymeter Data Released!");
                }

                fos.close();
            }
            catch (IOException ioe) {
                //
            }
        }
    }
}