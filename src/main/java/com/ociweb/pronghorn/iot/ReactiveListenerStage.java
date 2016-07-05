package com.ociweb.pronghorn.iot;

import com.ociweb.iot.maker.AnalogListener;
import com.ociweb.iot.maker.DigitalListener;
import com.ociweb.iot.maker.I2CListener;
import com.ociweb.iot.maker.RestListener;
import com.ociweb.iot.maker.RotaryListener;
import com.ociweb.iot.maker.StartupListener;
import com.ociweb.iot.maker.TimeListener;
import com.ociweb.pronghorn.iot.schema.GroveResponseSchema;
import com.ociweb.pronghorn.pipe.Pipe;
import com.ociweb.pronghorn.pipe.PipeReader;
import com.ociweb.pronghorn.pipe.RawDataSchema;
import com.ociweb.pronghorn.stage.PronghornStage;
import com.ociweb.pronghorn.stage.scheduling.GraphManager;

public class ReactiveListenerStage extends PronghornStage {

    private final Object              listener;
    private final Pipe<?>[]           pipes;
    
    private long                      timeTrigger;
    private long                      timeRate;
               
    
    public ReactiveListenerStage(GraphManager graphManager, Object listener, Pipe<?> ... pipes) {
        
        super(graphManager, pipes, NONE);
        this.listener = listener;
        this.pipes = pipes;                
    }

    public void setTimeEventSchedule(long rate) {
        
        timeRate = rate;
        long now = System.currentTimeMillis();
        if (timeTrigger <= now) {
            timeTrigger = now + timeRate;
        }
        
    }
    
    @Override
    public void startup() {
        if (listener instanceof StartupListener) {
            ((StartupListener)listener).startup();
        }
    }
    
    @Override
    public void run() {
        
        //TODO: replace with linked list of processors?, NOTE each one also needs a length bound so it does not starve the rest.
        
        int p = pipes.length;
        while (--p >= 0) {
            //TODO: this solution works but smells, a "process" lambda added to the Pipe may be a better solution? Still thinking....
            Pipe<?> localPipe = pipes[p];

            if (Pipe.isForSchema(localPipe, GroveResponseSchema.instance)) {
                consumeResponseMessage(listener, (Pipe<GroveResponseSchema>) localPipe);
            } else
            if (Pipe.isForSchema(localPipe, RawDataSchema.instance)) { //TODO: i2c needs its own schema
                
                consumeI2CMessage(listener, (Pipe<RawDataSchema>) localPipe);
            }
//            if (Pipe.isForSchema(localPipe, RestSomethingSchema.instance)) {
//                
//                consumeRestMessage(listener, restResponsePipes);
//            }
            else {
                //error
            }
        }
        
        processTimeEvents(listener);
        
    }


    private void processTimeEvents(Object listener) {
        //if we do have a clock schedule
        if (0 != timeRate) {
            long now = System.currentTimeMillis();
            if (now >= timeTrigger) {
                if (listener instanceof TimeListener) {
                    ((TimeListener)listener).timeEvent(now);
                    timeTrigger = now + timeRate;
                }
            }
        }
    }

    private void consumeRestMessage(Object listener2, Pipe<?> p) {
        if (null!= p) {
            
            while (PipeReader.tryReadFragment(p)) {                
                
                int msgIdx = PipeReader.getMsgIdx(p);
                
                //no need to check instance of since this was registered and we have a pipe
                ((RestListener)listener).restRequest(1, null, null);
                
                //done reading message off pipe
                PipeReader.releaseReadLock(p);
            }
            
        }
    }
    

    private void consumeI2CMessage(Object listener, Pipe<RawDataSchema> p) {
        while (PipeReader.tryReadFragment(p)) {                
                    
                    int msgIdx = PipeReader.getMsgIdx(p);
                    switch (msgIdx) {   
                        case RawDataSchema.MSG_CHUNKEDSTREAM_1:
                            if (listener instanceof I2CListener) {
                                
                                byte[] backing = PipeReader.readBytesBackingArray(p, RawDataSchema.MSG_CHUNKEDSTREAM_1_FIELD_BYTEARRAY_2);
                                int position = PipeReader.readBytesPosition(p, RawDataSchema.MSG_CHUNKEDSTREAM_1_FIELD_BYTEARRAY_2);
                                int length = PipeReader.readBytesLength(p, RawDataSchema.MSG_CHUNKEDSTREAM_1_FIELD_BYTEARRAY_2);
                                int mask = PipeReader.readBytesMask(p, RawDataSchema.MSG_CHUNKEDSTREAM_1_FIELD_BYTEARRAY_2);
                                byte addr = backing[mask&position];//hack to get addr for now, new schema will have this split out.
                                
                                ((I2CListener)listener).i2cEvent(addr, backing, position, length, mask);
                               
                            }
                            break;
                        case -1:
                            
                            requestShutdown();
                            PipeReader.releaseReadLock(p);
                            return;
                           
                        default:
                            throw new UnsupportedOperationException("Unknown id: "+msgIdx);
                        
                    }
                    //done reading message off pipe
                    PipeReader.releaseReadLock(p);
        }
    }

    private void consumeResponseMessage(Object listener, Pipe<GroveResponseSchema> p) {
        while (PipeReader.tryReadFragment(p)) {                
            
            int msgIdx = PipeReader.getMsgIdx(p);
            switch (msgIdx) {   

                case GroveResponseSchema.MSG_ANALOGSAMPLE_30:
                    if (listener instanceof AnalogListener) {
                        
                        int connector = PipeReader.readInt(p, GroveResponseSchema.MSG_ANALOGSAMPLE_30_FIELD_CONNECTOR_31);
                        long time = PipeReader.readLong(p, GroveResponseSchema.MSG_ANALOGSAMPLE_30_FIELD_TIME_11);
                        int average = PipeReader.readInt(p, GroveResponseSchema.MSG_ANALOGSAMPLE_30_FIELD_AVERAGE_33);
                        int value = PipeReader.readInt(p, GroveResponseSchema.MSG_ANALOGSAMPLE_30_FIELD_VALUE_32);
                        
                        ((AnalogListener)listener).analogEvent(connector, time, average, value);
                        
                    }   
                break;               
                case GroveResponseSchema.MSG_DIGITALSAMPLE_20:
                    if (listener instanceof DigitalListener) {
                        int connector = PipeReader.readInt(p, GroveResponseSchema.MSG_DIGITALSAMPLE_20_FIELD_CONNECTOR_21);
                        long time = PipeReader.readLong(p, GroveResponseSchema.MSG_DIGITALSAMPLE_20_FIELD_TIME_11);
                        int value = PipeReader.readInt(p, GroveResponseSchema.MSG_DIGITALSAMPLE_20_FIELD_VALUE_22);
                                                    
                        ((DigitalListener)listener).digitalEvent(connector, time, value);
                        
                    }   
                break; 
                case GroveResponseSchema.MSG_ENCODER_70:
                    if (listener instanceof RotaryListener) {    
                        int connector = PipeReader.readInt(p, GroveResponseSchema.MSG_ENCODER_70_FIELD_CONNECTOR_71);
                        long time = PipeReader.readLong(p, GroveResponseSchema.MSG_ENCODER_70_FIELD_TIME_11);
                        int value = PipeReader.readInt(p, GroveResponseSchema.MSG_ENCODER_70_FIELD_VALUE_72);
                        int delta = PipeReader.readInt(p, GroveResponseSchema.MSG_ENCODER_70_FIELD_DELTA_73);
                        int speed = PipeReader.readInt(p, GroveResponseSchema.MSG_ENCODER_70_FIELD_SPEED_74);
                        
                        ((RotaryListener)listener).rotaryEvent(connector, time, value, delta, speed);
                                            
                    }   
                break;
                case -1:
                {    
                    requestShutdown();
                    PipeReader.releaseReadLock(p);
                    return;
                }   
                default:
                    throw new UnsupportedOperationException("Unknown id: "+msgIdx);
            }               
            
            //done reading message off pipe
            PipeReader.releaseReadLock(p);
        }
    }
    
    
    
    
}