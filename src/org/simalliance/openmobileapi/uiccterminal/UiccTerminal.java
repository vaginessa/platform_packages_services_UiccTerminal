package org.simalliance.openmobileapi.uiccterminal;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.telephony.IccOpenLogicalChannelResponse;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.TelephonyProperties;

import java.util.List;
import java.util.ArrayList;
import java.util.MissingResourceException;
import java.util.NoSuchElementException;


import org.simalliance.openmobileapi.service.CardException;
import org.simalliance.openmobileapi.service.ITerminalService;

/**
 * Created by sevilser on 18/12/14.
 */
public final class UiccTerminal extends Service {

    private static final String TAG = "UiccTerminal";

    private TelephonyManager manager = null;

    private List<Integer> channelIds;

    private String currentSelectedFilePath = "";

    private static byte[] mAtr = null;

    @Override
    public IBinder onBind(Intent intent) {
        return mTerminalBinder;
    }

    @Override
    public void onCreate() {
        // Constructor
        channelIds = new ArrayList<Integer>();
        // Occupy channelIds[0] to avoid return channel number = 0 on openLogicalChannel
        channelIds.add(0xFFFFFFFF);

        manager = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
    }

    private final ITerminalService.Stub mTerminalBinder = new ITerminalService.Stub() {

        @Override
        public String getType() {
            return UiccTerminal.getType();
        }

        @Override
        public org.simalliance.openmobileapi.service.OpenLogicalChannelResponse internalOpenLogicalChannel(byte[] aid, org.simalliance.openmobileapi.service.SmartcardError error) throws RemoteException {
            String aidString;
            if (aid == null) {
                aidString = "";
            } else {
                aidString = byteArrayToString(aid, 0);
            }

            try {
                return iccOpenLogicalChannel(aidString);
            } catch (Exception e) {
                error.setError(e.getClass(), e.getMessage());
                throw new RemoteException();
            }
        }

        @Override
        public void internalCloseLogicalChannel(int channelNumber, org.simalliance.openmobileapi.service.SmartcardError error)
                throws RemoteException {
            if (channelNumber == 0) {
                return;
            }
            if (channelIds.get(channelNumber) == 0) {
                error.setError(org.simalliance.openmobileapi.service.CardException.class, "channel not open");
                throw new RemoteException();
            }
            try {
                if (!manager.iccCloseLogicalChannel(channelIds.get(channelNumber))) {
                    error.setError(org.simalliance.openmobileapi.service.CardException.class, "close channel failed");
                    throw new RemoteException();
                }
            } catch (Exception ex) {
                error.setError(org.simalliance.openmobileapi.service.CardException.class, "close channel failed");
                throw new RemoteException();
            }
            channelIds.set(channelNumber, 0);
        }

        @Override
        public byte[] internalTransmit(byte[] command, org.simalliance.openmobileapi.service.SmartcardError error) throws RemoteException {
            Log.d(TAG, "internalTransmit > " + byteArrayToString(command, 0));
            int cla = clearChannelNumber(command[0]) & 0xff;
            int ins = command[1] & 0xff;
            int p1 = command[2] & 0xff;
            int p2 = command[3] & 0xff;
            int p3 = -1;
            if (command.length > 4) {
                p3 = command[4] & 0xff;
            }
            String data = null;
            if (command.length > 5) {
                data = byteArrayToString(command, 5);
            }

            int channelNumber = parseChannelNumber(command[0]);

            String response= "";
            if (channelNumber == 0) {
                try {
                    response = manager.iccTransmitApduBasicChannel(
                            cla, ins, p1, p2, p3, data);
                } catch (Exception ex) {
                    error.setError(org.simalliance.openmobileapi.service.CardException.class, "transmit command failed");
                    throw new RemoteException();
                }
            } else {
                if ((channelNumber > 0) && (channelIds.get(channelNumber) == 0)) {
                    error.setError(org.simalliance.openmobileapi.service.CardException.class, "channel not open");
                    throw new RemoteException();
                }

                try {
                    response = manager.iccTransmitApduLogicalChannel(
                            channelIds.get(channelNumber), cla, ins, p1, p2, p3, data);
                } catch (Exception ex) {
                    error.setError(org.simalliance.openmobileapi.service.CardException.class, "transmit command failed");
                    throw new RemoteException();
                }
            }
            Log.d(TAG, "internalTransmit < " + response);
            return stringToByteArray(response);
        }

        @Override
        public byte[] getAtr() {
            if (mAtr == null) {
                String atr = manager.iccGetAtr();
                Log.d(TAG, "atr = " + atr == null ? "" : atr);
                if (atr != null && atr != "") {
                    mAtr = stringToByteArray(atr);
                }
            }
            return mAtr;
        }

        @Override
        public boolean isCardPresent() throws RemoteException {
            if (manager == null) {
                return false;
            }
            String simState = SystemProperties
                    .get(TelephonyProperties.PROPERTY_SIM_STATE);
            return simState.equals("READY");
        }
    };

    private byte[] stringToByteArray(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    private String byteArrayToString(byte[] b, int start) {
        if (b == null) {
            return "";
        }
        StringBuffer s = new StringBuffer();
        for (int i = start; i < b.length; i++) {
            s.append(Integer.toHexString(0x100 + (b[i] & 0xff)).substring(1));
        }
        return s.toString();
    }

    /**
     * Clear the channel number.
     *
     * @param cla
     *
     * @return the cla without channel number
     */
    private byte clearChannelNumber(byte cla) {
        // bit 7 determines which standard is used
        boolean isFirstInterindustryClassByteCoding = ((cla & 0x40) == 0x00);

        if (isFirstInterindustryClassByteCoding) {
            // First Interindustry Class Byte Coding
            // see 11.1.4.1: channel number is encoded in the 2 rightmost bits
            return (byte) (cla & 0xFC);
        } else {
            // Further Interindustry Class Byte Coding
            // see 11.1.4.2: channel number is encoded in the 4 rightmost bits
            return (byte) (cla & 0xF0);
        }
    }

    /**
     * Exchanges APDU (SELECT, READ/WRITE) to the  given EF by File ID and file
     * path via iccIO.
     *
     * The given command is checked and might be rejected.
     *
     * @param fileID
     * @param filePath
     * @param cmd
     * @return
     */
    //@Override TODO
    public byte[] simIOExchange(int fileID, String filePath, byte[] cmd)
            throws Exception {
        try {
            int ins = 0;
            int p1 = cmd[2] & 0xff;
            int p2 = cmd[3] & 0xff;
            int p3 = cmd[4] & 0xff;
            switch(cmd[1]) {
                case (byte) 0xB0:
                    ins = 176;
                    break;
                case (byte) 0xB2:
                    ins = 178;
                    break;
                case (byte) 0xA4:
                    ins = 192;
                    p1 = 0;
                    p2 = 0;
                    p3 = 15;
                    break;
                default:
                    // TODO
                    // throw new SecureElementException("Unknown SIM_IO command");
            }

            if (filePath != null && filePath.length() > 0) {
                currentSelectedFilePath = filePath;
            }

            // TODO: check if transmitIccSimIO is really required
            return manager.iccExchangeSimIO(
                    fileID, ins, p1, p2, p3, currentSelectedFilePath);
        } catch (Exception e) {
            throw new Exception("SIM IO access error");
        }
    }

    /**
     * Extracts the channel number from a CLA byte. Specified in GlobalPlatform
     * Card Specification 2.2.0.7: 11.1.4 Class Byte Coding.
     *
     * @param cla
     *            the command's CLA byte
     * @return the channel number within [0x00..0x0F]
     */
    private int parseChannelNumber(byte cla) {
        // bit 7 determines which standard is used
        boolean isFirstInterindustryClassByteCoding = ((cla & 0x40) == 0x00);

        if (isFirstInterindustryClassByteCoding) {
            // First Interindustry Class Byte Coding
            // see 11.1.4.1: channel number is encoded in the 2 rightmost bits
            return cla & 0x03;
        } else {
            // Further Interindustry Class Byte Coding
            // see 11.1.4.2: channel number is encoded in the 4 rightmost bits
            return (cla & 0x0F) + 4;
        }
    }




    /**
     * Performs all the logic for opening a logical channel.
     *
     * @param aid The AID to which the channel shall be opened, empty string to
     * specify "no AID".
     *
     * @return The index of the opened channel ID in the channelIds list.
     */
    private org.simalliance.openmobileapi.service.OpenLogicalChannelResponse iccOpenLogicalChannel(String aid) throws Exception {
        Log.d(TAG, "iccOpenLogicalChannel > " + aid);
        // Remove any previously stored selection response
        IccOpenLogicalChannelResponse response = manager.iccOpenLogicalChannel(aid);
        int status = response.getStatus();
        if (status != IccOpenLogicalChannelResponse.STATUS_NO_ERROR) {
            Log.d(TAG, "iccOpenLogicalChannel failed.");
            // An error occured.
            if (status == IccOpenLogicalChannelResponse.STATUS_MISSING_RESOURCE) {
                throw new MissingResourceException(
                        "all channels are used", "", "");
            }
            if (status == IccOpenLogicalChannelResponse.STATUS_NO_SUCH_ELEMENT) {
                throw new NoSuchElementException("applet not found");
            }
            throw new CardException("open channel failed");
        }
        // Operation succeeded
        // Set the select response
        Log.d(TAG, "iccOpenLogicalChannel < " + byteArrayToString(response.getSelectResponse(), 0));
        // Save channel ID. First check if there is any channelID which is empty
        // to reuse it.
        for (int i = 1; i < channelIds.size(); i++) {
            if (channelIds.get(i) == 0) {
                channelIds.set(i, response.getChannel());
                return new org.simalliance.openmobileapi.service.OpenLogicalChannelResponse(i, response.getSelectResponse());
            }
        }
        // If no channel ID is empty, append one at the end of the list.
        channelIds.add(response.getChannel());
        return new org.simalliance.openmobileapi.service.OpenLogicalChannelResponse(channelIds.size() - 1, response.getSelectResponse());
    }

    public static String getType() {
        return "SIM";
    }
}
