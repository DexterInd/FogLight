package com.ociweb.iot.grove;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.ociweb.iot.hardware.I2CConnection;
import com.ociweb.iot.hardware.IODevice;
import com.ociweb.pronghorn.iot.schema.GroveResponseSchema;
import com.ociweb.pronghorn.pipe.Pipe;

public class TempAndHumidTwig implements IODevice{

	public byte addr = 0x04;
	private byte[] tempData = new byte[4];
	private byte[] humData = new byte[4];
	private byte connection;
	private byte module_type;
	public TempAndHumidTwig(byte connection, byte module_type) {
		this.connection = connection;
		this.module_type=module_type;
	}

	public I2CConnection getI2CConnection(){
		assert(module_type == 22 || module_type == 21) : "Please enter a valid DHT module type (21 or 22)";
		byte[] TEMPHUMID_READCMD = {0x01, 0x40, connection, module_type, 0x00};
	    byte[] TEMPHUMID_SETUP = {0x01, 0x05, connection, 0x00, 0x00}; //set pinMode to output
	    byte TEMPHUMID_ADDR = 0x04;
	    byte TEMPHUMID_BYTESTOREAD = 4;
	    byte TEMPHUMID_REGISTER = connection;
	    return new I2CConnection(this, TEMPHUMID_ADDR, TEMPHUMID_READCMD, TEMPHUMID_BYTESTOREAD, TEMPHUMID_REGISTER, TEMPHUMID_SETUP);
	}
	
	public float[] interpretGrovePiData(int register, long time, byte[] backing, int position, int length, int mask){
		assert(length == 8) : "Incorrect length of data passed into DHT sensor class";
		for (int i = 0; i < tempData.length; i++) {
			tempData[i]= backing[(position+i)%mask];
		}
		for (int i = 0; i < humData.length; i++) {
			humData[i]= backing[(position+i)%mask];
		}
		float[] temp = {ByteBuffer.wrap(tempData).order(ByteOrder.LITTLE_ENDIAN).getFloat(), 
				ByteBuffer.wrap(humData).order(ByteOrder.LITTLE_ENDIAN).getFloat()};
		return temp;
	}

	@Override
	public boolean isInput() {
		return true;
	}

	@Override
	public boolean isOutput() {
		return false;
	}

	@Override
	public boolean isPWM() {
		return false;
	}

	@Override
	public int pwmRange() {
		return 0;
	}

//	@Override
//	public boolean isI2C() {
//		return true;
//	}


	@Override
	public boolean isGrove() {
		return false; //this has to be false
	}

}