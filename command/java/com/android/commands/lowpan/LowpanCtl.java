/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.commands.lowpan;

import android.net.lowpan.ILowpanInterface;
import android.net.lowpan.LowpanBeaconInfo;
import android.net.lowpan.LowpanCredential;
import android.net.lowpan.LowpanEnergyScanResult;
import android.net.lowpan.LowpanException;
import android.net.lowpan.LowpanIdentity;
import android.net.lowpan.LowpanInterface;
import android.net.lowpan.LowpanManager;
import android.net.lowpan.LowpanProvision;
import android.net.lowpan.LowpanScanner;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.AndroidRuntimeException;
import com.android.internal.os.BaseCommand;
import com.android.internal.util.HexDump;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class LowpanCtl extends BaseCommand {
    private LowpanManager mLowpanManager;
    private LowpanInterface mLowpanInterface;
    private ILowpanInterface mILowpanInterface;
    private String mLowpanInterfaceName;

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        new LowpanCtl().run(args);
    }

    @Override
    public void onShowUsage(PrintStream out) {
        out.println(
                "usage: lowpanctl [options] [subcommand] [subcommand-options]\n"
                        + "       lowpanctl status\n"
                        + "       lowpanctl form\n"
                        + "       lowpanctl join\n"
                        + "       lowpanctl leave\n"
                        + "       lowpanctl up\n"
                        + "       lowpanctl down\n"
                        + "       lowpanctl get [property-name]\n"
                        + "       lowpanctl set [property-name]\n"
                        + "       lowpanctl scan\n"
                        + "       lowpanctl reset\n"
                        + "       lowpanctl list\n"
                        + "\n");
    }

    private class CommandErrorException extends AndroidRuntimeException {
        public CommandErrorException(String desc) {
            super(desc);
        }
    }

    private void throwCommandError(String desc) {
        throw new CommandErrorException(desc);
    }

    private LowpanInterface getLowpanInterface() {
        if (mLowpanInterface == null) {
            if (mLowpanInterfaceName == null) {
                String interfaceArray[] = mLowpanManager.getInterfaceList();
                if (interfaceArray.length != 0) {
                    mLowpanInterfaceName = interfaceArray[0];
                } else {
                    throwCommandError("No LoWPAN interfaces are present");
                }
            }
            mLowpanInterface = mLowpanManager.getInterface(mLowpanInterfaceName);

            if (mLowpanInterface == null) {
                throwCommandError("Unknown LoWPAN interface \"" + mLowpanInterfaceName + "\"");
            }
        }
        return mLowpanInterface;
    }

    private ILowpanInterface getILowpanInterface() {
        if (mILowpanInterface == null) {
            mILowpanInterface = getLowpanInterface().getService();
        }
        return mILowpanInterface;
    }

    @Override
    public void onRun() throws Exception {
        mLowpanManager = LowpanManager.getManager();

        if (mLowpanManager == null) {
            System.err.println(NO_SYSTEM_ERROR_CODE);
            throwCommandError("Can't connect to LoWPAN service; is the service running?");
        }

        try {
            String op;
            while ((op = nextArgRequired()) != null) {
                if (op.equals("-I") || op.equals("--interface")) {
                    mLowpanInterfaceName = nextArgRequired();
                } else if (op.startsWith("-")) {
                    throwCommandError("Unrecognized argument \"" + op + "\"");
                } else if (op.equals("status") || op.equals("stat")) {
                    runStatus();
                    break;
                } else if (op.equals("scan") || op.equals("netscan") || op.equals("ns")) {
                    runNetScan();
                    break;
                } else if (op.equals("attach") || op.equals("up")) {
                    runAttach();
                    break;
                } else if (op.equals("detach") || op.equals("down")) {
                    runDetach();
                    break;
                } else if (op.equals("join")) {
                    runJoin();
                    break;
                } else if (op.equals("form")) {
                    runForm();
                    break;
                } else if (op.equals("leave")) {
                    runLeave();
                    break;
                } else if (op.equals("get") || op.equals("getprop")) {
                    runGetProp();
                    break;
                } else if (op.equals("set") || op.equals("setprop")) {
                    runSetProp();
                    break;
                } else if (op.equals("energyscan") || op.equals("energy") || op.equals("es")) {
                    runEnergyScan();
                    break;
                } else if (op.equals("list") || op.equals("ls")) {
                    runListInterfaces();
                    break;
                } else if (op.equals("reset")) {
                    runReset();
                    break;
                } else {
                    showError("Error: unknown command '" + op + "'");
                    break;
                }
            }
        } catch (ServiceSpecificException x) {
            System.out.println(
                    "ServiceSpecificException: " + x.errorCode + ": " + x.getLocalizedMessage());
        } catch (CommandErrorException x) {
            System.out.println("error: " + x.getLocalizedMessage());
        }
    }

    private void runReset() throws LowpanException {
        getLowpanInterface().reset();
    }

    private LowpanProvision getProvisionFromArgs(boolean credentialRequired) {
        LowpanProvision.Builder builder = new LowpanProvision.Builder();
        Map<String, Object> properties = new HashMap();
        LowpanIdentity.Builder identityBuilder = new LowpanIdentity.Builder();
        LowpanCredential credential = null;
        String arg;
        byte[] masterKey = null;
        int masterKeyIndex = 0;
        boolean hasName = false;

        while ((arg = nextArg()) != null) {
            if (arg.equals("--name")) {
                identityBuilder.setName(nextArgRequired());
                hasName = true;
            } else if (arg.equals("-p") || arg.equals("--panid")) {
                identityBuilder.setPanid(Integer.decode(nextArgRequired()));
            } else if (arg.equals("-c") || arg.equals("--channel")) {
                identityBuilder.setChannel(Integer.decode(nextArgRequired()));
            } else if (arg.equals("-x") || arg.equals("--xpanid")) {
                identityBuilder.setXpanid(HexDump.hexStringToByteArray(nextArgRequired()));
            } else if (arg.equals("-k") || arg.equals("--master-key")) {
                masterKey = HexDump.hexStringToByteArray(nextArgRequired());
            } else if (arg.equals("--master-key-index")) {
                masterKeyIndex = Integer.decode(nextArgRequired());
            } else if (arg.startsWith("-") || hasName) {
                throwCommandError("Unrecognized argument \"" + arg + "\"");
            } else {
                // This is the network name
                identityBuilder.setName(arg);
                hasName = true;
            }
        }

        if (credential == null && masterKey != null) {
            if (masterKeyIndex == 0) {
                credential = LowpanCredential.createMasterKey(masterKey);
            } else {
                credential = LowpanCredential.createMasterKey(masterKey, masterKeyIndex);
            }
        }

        if (credential != null) {
            builder.setLowpanCredential(credential);
        } else if (credentialRequired) {
            throwCommandError("No credential (like a master key) was specified!");
        }

        return builder.setLowpanIdentity(identityBuilder.build()).build();
    }

    private void runAttach() throws LowpanException {
        LowpanProvision provision = getProvisionFromArgs(true);

        System.out.println(
                "Attaching to " + provision.getLowpanIdentity() + " with provided credential");

        getLowpanInterface().attach(provision);

        System.out.println("Attached.");
    }

    private void runDetach() throws LowpanException {
        getLowpanInterface().setUp(false);
    }

    private void runLeave() throws LowpanException {
        getLowpanInterface().leave();
    }

    private void runJoin() throws LowpanException {
        LowpanProvision provision = getProvisionFromArgs(true);

        System.out.println(
                "Joining " + provision.getLowpanIdentity() + " with provided credential");

        getLowpanInterface().join(provision);

        System.out.println("Joined.");
    }

    private void runForm() throws LowpanException {
        LowpanProvision provision = getProvisionFromArgs(false);

        if (provision.getLowpanCredential() != null) {
            System.out.println(
                    "Forming "
                            + provision.getLowpanIdentity().toString()
                            + " with provided credential");
        } else {
            System.out.println("Forming " + provision.getLowpanIdentity().toString());
        }

        getLowpanInterface().form(provision);

        System.out.println("Formed.");
    }

    private String propAsString(String key, Object value) {
        if (value instanceof byte[]) {
            value = HexDump.toHexString((byte[]) value);
        } else if (value instanceof String[]) {
            if (((String[]) value).length == 0) {
                value = "{ }";
            } else {
                String renderedValue = "{\n";
                for (String row : (String[]) value) {
                    renderedValue += "\t\"" + row + "\"\n";
                }
                value = renderedValue + "}";
            }
        } else if (value instanceof int[]) {
            if (((int[]) value).length == 0) {
                value = "{ }";
            } else {
                String renderedValue = "{\n";
                for (int row : (int[]) value) {
                    renderedValue += "\t" + Integer.toString(row) + "\n";
                }
                value = renderedValue + "}";
            }
        } else if ((value instanceof Long) && (key.equals(ILowpanInterface.KEY_NETWORK_XPANID))) {
            value = "0x" + Long.toHexString((Long) value);
        } else if ((value instanceof Integer)
                && (key.equals(ILowpanInterface.KEY_NETWORK_PANID)
                        || key.equals("Thread:RLOC16"))) {
            value = String.format("0x%04X", (Integer) value & 0xFFFF);
        }
        return value.toString();
    }

    private void runGetProp() throws LowpanException, RemoteException {
        String key = nextArg();

        if (key == null) {
            try {
                String key_list[] = getILowpanInterface().getPropertyKeys();

                for (String subkey : key_list) {
                    Object value;
                    try {
                        value = getILowpanInterface().getPropertyAsString(subkey);
                    } catch (Exception x) {
                        value = x;
                    }
                    System.out.println(subkey + " => " + propAsString(subkey, value));
                }
            } catch (RemoteException x) {
                x.rethrowAsRuntimeException();
            }
        } else {
            Object value = getILowpanInterface().getPropertyAsString(key);
            System.out.println(propAsString(key, value));
        }
    }

    private void runSetProp() {
        System.out.println("Command not implemented");
    }

    private void runStatus() throws LowpanException, RemoteException {
        String statusKeys[] = {
            ILowpanInterface.KEY_INTERFACE_ENABLED,
            ILowpanInterface.KEY_INTERFACE_STATE,
            "org.wpantund.Daemon:Version",
            "org.wpantund.NCP:Version",
            "org.wpantund.Config:NCP:DriverName",
            "org.wpantund.IPv6:LinkLocalAddress",
            "org.wpantund.IPv6:MeshLocalAddress",
        };
        System.out.println(
                "Current Network => " + getLowpanInterface().getLowpanIdentity().toString());

        for (String key : statusKeys) {
            Object value;
            try {
                value = getILowpanInterface().getPropertyAsString(key);
                if (value != null) {
                    System.out.println(key + " => " + propAsString(key, value));
                }
            } catch (ServiceSpecificException x) {
                // Skip keys which cause remote exceptions.
            }
        }
    }

    private void runListInterfaces() {
        for (String name : mLowpanManager.getInterfaceList()) {
            System.out.println(name);
        }
    }

    private void runNetScan() throws LowpanException, InterruptedException {
        LowpanScanner scanner = getLowpanInterface().createScanner();
        String arg;

        while ((arg = nextArg()) != null) {
            if (arg.equals("-c") || arg.equals("--channel")) {
                scanner.addChannel(Integer.decode(nextArgRequired()));
            } else {
                throwCommandError("Unrecognized argument \"" + arg + "\"");
            }
        }

        Semaphore semaphore = new Semaphore(1);

        scanner.setCallback(
                new LowpanScanner.Callback() {
                    @Override
                    public void onNetScanBeacon(LowpanBeaconInfo beacon) {
                        System.out.println(beacon.toString());
                    }

                    @Override
                    public void onScanFinished() {
                        semaphore.release();
                    }
                });

        semaphore.acquire();
        scanner.startNetScan();

        // Wait for our scan to complete.
        if (semaphore.tryAcquire(1, 60L, TimeUnit.SECONDS)) {
            semaphore.release();
        } else {
            throwCommandError("Timeout while waiting for scan to complete.");
        }
    }

    private void runEnergyScan() throws LowpanException, InterruptedException {
        LowpanScanner scanner = getLowpanInterface().createScanner();
        String arg;

        while ((arg = nextArg()) != null) {
            if (arg.equals("-c") || arg.equals("--channel")) {
                scanner.addChannel(Integer.decode(nextArgRequired()));
            } else {
                throwCommandError("Unrecognized argument \"" + arg + "\"");
            }
        }

        Semaphore semaphore = new Semaphore(1);

        scanner.setCallback(
                new LowpanScanner.Callback() {
                    @Override
                    public void onEnergyScanResult(LowpanEnergyScanResult result) {
                        System.out.println(result.toString());
                    }

                    @Override
                    public void onScanFinished() {
                        semaphore.release();
                    }
                });

        semaphore.acquire();
        scanner.startEnergyScan();

        // Wait for our scan to complete.
        if (semaphore.tryAcquire(1, 60L, TimeUnit.SECONDS)) {
            semaphore.release();
        } else {
            throwCommandError("Timeout while waiting for scan to complete.");
        }
    }
}
