/*
 * Copyright (c) 2019 Victor Antonovich <v.antonovich@gmail.com>
 *
 * This work is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This work is distributed in the hope that it will be useful, but
 * without any warranty; without even the implied warranty of merchantability
 * or fitness for a particular purpose. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.
 */

package com.github.ykc3.android.usbi2c.adapter;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;

import com.github.ykc3.android.usbi2c.UsbI2cAdapter;
import com.github.ykc3.android.usbi2c.UsbI2cDevice;
import com.github.ykc3.android.usbi2c.UsbI2cManager;

import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;

abstract class UsbI2cBaseAdapter implements UsbI2cAdapter {
    // Linux kernel flags
    static final int I2C_M_RD = 0x01; // read data, from slave to master

    protected static final int USB_TIMEOUT_MILLIS = 1000;

    protected final UsbI2cManager i2cManager;
    protected final UsbDevice usbDevice;

    protected UsbDeviceConnection usbDeviceConnection;

    protected static final int MAX_MESSAGE_SIZE = 8192;

    private final byte[] buffer = new byte[MAX_MESSAGE_SIZE + 1];

    protected final ReentrantLock accessLock = new ReentrantLock();

    protected abstract class UsbI2cBaseDevice implements UsbI2cDevice {
        final int address;

        UsbI2cBaseDevice(int address) {
            this.address = (address & 0x7f);
        }

        @Override
        public int getAddress() {
            return address;
        }

        @Override
        public byte readRegByte(int reg) throws IOException {
            try {
                accessLock.lock();
                readRegBuffer(reg, buffer, 1);
                return buffer[0];
            } finally {
                accessLock.unlock();
            }
        }

        @Override
        public short readRegWord(int reg) throws IOException {
            try {
                accessLock.lock();
                readRegBuffer(reg, buffer, 2);
                return (short) ((buffer[0] & 0xFF) | (buffer[1] << 8));
            } finally {
                accessLock.unlock();
            }
        }

        @Override
        public void writeRegByte(int reg, byte data) throws IOException {
            try {
                accessLock.lock();
                buffer[0] = (byte) reg;
                buffer[1] = data;
                write(buffer, 2);
            } finally {
                accessLock.unlock();
            }
        }

        @Override
        public void writeRegWord(int reg, short data) throws IOException {
            try {
                accessLock.lock();
                buffer[0] = (byte) reg;
                buffer[1] = (byte) data;
                buffer[2] = (byte) (data >>> 8);
                write(buffer, 3);
            } finally {
                accessLock.unlock();
            }
        }

        @Override
        public void writeRegBuffer(int reg, byte[] buffer, int length) throws IOException {
            try {
                accessLock.lock();
                int len = Math.min(length, MAX_MESSAGE_SIZE);
                buffer[0] = (byte) reg;
                System.arraycopy(buffer, 0, UsbI2cBaseAdapter.this.buffer, 1, len);
                write(UsbI2cBaseAdapter.this.buffer, len + 1);
            } finally {
                accessLock.unlock();
            }
        }

        @Override
        public void readRegBuffer(int reg, byte[] buffer, int length) throws IOException {
            try {
                accessLock.lock();
                deviceReadReg(reg, buffer, length);
            } finally {
                accessLock.unlock();
            }
        }

        @Override
        public void read(byte[] buffer, int length) throws IOException {
            try {
                accessLock.lock();
                deviceRead(buffer, length);
            } finally {
                accessLock.unlock();
            }
        }

        @Override
        public void write(byte[] buffer, int length) throws IOException {
            try {
                accessLock.lock();
                deviceWrite(buffer, length);
            } finally {
                accessLock.unlock();
            }
        }

        protected abstract void deviceReadReg(int reg, byte[] buffer, int length) throws IOException;

        protected abstract void deviceWrite(byte[] buffer, int length) throws IOException;

        protected abstract void deviceRead(byte[] buffer, int length) throws IOException;
    }

    UsbI2cBaseAdapter(UsbI2cManager i2cManager, UsbDevice usbDevice) {
        this.i2cManager = i2cManager;
        this.usbDevice = usbDevice;
    }

    @Override
    public String getId() {
        return usbDevice.getDeviceName();
    }

    @Override
    public void open() throws IOException {
        if (usbDeviceConnection != null) {
            throw new IllegalStateException("Already opened");
        }

        usbDeviceConnection = i2cManager.getUsbManager().openDevice(usbDevice);

        for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
            UsbInterface usbDeviceInterface = usbDevice.getInterface(i);
            if (!usbDeviceConnection.claimInterface(usbDeviceInterface, true)) {
                throw new IOException("Can't claim interface");
            }
        }

        openDevice(usbDevice);
    }

    protected void openDevice(UsbDevice usbDevice) throws IOException {
        // Do nothing by default
    }

    @Override
    public void close() throws Exception {
        closeDevice(usbDevice);

        if (usbDeviceConnection != null) {
            for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
                UsbInterface usbDeviceInterface = usbDevice.getInterface(i);
                usbDeviceConnection.releaseInterface(usbDeviceInterface);
            }
            usbDeviceConnection.close();
        }
    }

    protected void closeDevice(UsbDevice usbDevice) throws IOException {
        // Do nothing by default
    }

    @Override
    public UsbDevice getUsbDevice() {
        return usbDevice;
    }

    final void controlTransfer(int requestType, int request, int value,
                               int index, byte[] data, int length) throws IOException {
        int result = usbDeviceConnection.controlTransfer(requestType, request, value,
                index, data, length, USB_TIMEOUT_MILLIS);
        if (result != length) {
            throw new IOException(String.format("controlTransfer(requestType: 0x%x, " +
                            "request: 0x%x, value: 0x%x, index: 0x%x, length: %d) failed: %d",
                    requestType, request, value, index, length, result));
        }
    }
}
