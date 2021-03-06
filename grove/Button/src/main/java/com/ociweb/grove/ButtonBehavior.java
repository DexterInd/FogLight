package com.ociweb.grove;
import static com.ociweb.iot.maker.FogCommandChannel.PIN_WRITER;
import static com.ociweb.iot.maker.Port.D7;

import com.ociweb.iot.maker.DigitalListener;
import com.ociweb.iot.maker.FogCommandChannel;
import com.ociweb.iot.maker.FogRuntime;
import com.ociweb.iot.maker.Port;

public class ButtonBehavior implements DigitalListener {

	private static final Port RELAY_PORT = D7;
	
    final FogCommandChannel channel1;
	public ButtonBehavior(FogRuntime runtime) {

       channel1 = runtime.newCommandChannel(PIN_WRITER);

	}

	@Override
	public void digitalEvent(Port port, long time, long durationMillis, int value) {

        channel1.setValueAndBlock(RELAY_PORT, value == 1, 500); //500 is the amount of time in milliseconds that                                                                                         //delays a future action

	}

}
