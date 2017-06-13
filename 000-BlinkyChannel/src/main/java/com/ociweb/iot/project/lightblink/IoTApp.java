package com.ociweb.iot.project.lightblink;

import static com.ociweb.iot.grove.GroveTwig.LED;
import static com.ociweb.iot.maker.Port.*;
import com.ociweb.iot.maker.DeviceRuntime;
import com.ociweb.iot.maker.Hardware;
import com.ociweb.iot.maker.IoTSetup;
import com.ociweb.iot.maker.Port;
import com.ociweb.pronghorn.network.http.HTTP1xRouterStage;

public class IoTApp implements IoTSetup {
    
	public static Port LED_PORT = D5;
	
    public static void main( String[] args) {
        DeviceRuntime.run(new IoTApp());
    }    
    
    @Override
    public void declareConnections(Hardware c) {
        c.connect(LED, LED_PORT);
        c.setTriggerRate(100);
        
        HTTP1xRouterStage.showHeader=true;
       	
        c.enableTelemetry(true);        
    }

    @Override
    public void declareBehavior(DeviceRuntime runtime) {
    	runtime.addTimeListener(new BlinkerBehavior(runtime));
    	        
    }  
}
