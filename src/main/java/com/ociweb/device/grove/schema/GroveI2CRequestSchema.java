package com.ociweb.device.grove.schema;

import com.ociweb.pronghorn.pipe.FieldReferenceOffsetManager;
import com.ociweb.pronghorn.pipe.MessageSchema;

public class GroveI2CRequestSchema extends MessageSchema{

    public final static FieldReferenceOffsetManager FROM = new FieldReferenceOffsetManager(
            new int[]{0xc0400004,0x80000000,0x80000001,0x80000002,0xc0200004,0xc0400002,0xa0000000,0xc0200002},
            (short)0,
            new String[]{"LCDRGBBackLight","Red","Greed","Blue",null,"LCDRGBText","text",null},
            new long[]{200, 201, 202, 203, 0, 210, 211, 0},
            new String[]{"global",null,null,null,null,"global",null,null},
            "GroveI2CRequest.xml",
            new long[]{2, 2, 0},
            new int[]{2, 2, 0});
    
    public static final int MSG_LCDRGBBACKLIGHT_200 = 0x00000000;
    public static final int MSG_LCDRGBBACKLIGHT_200_FIELD_RED_201 = 0x00000001;
    public static final int MSG_LCDRGBBACKLIGHT_200_FIELD_GREED_202 = 0x00000002;
    public static final int MSG_LCDRGBBACKLIGHT_200_FIELD_BLUE_203 = 0x00000003;
    public static final int MSG_LCDRGBTEXT_210 = 0x00000005;
    public static final int MSG_LCDRGBTEXT_210_FIELD_TEXT_211 = 0x01000001;
    
    public static final GroveI2CRequestSchema instance = new GroveI2CRequestSchema();
    
    private GroveI2CRequestSchema() {
        super(FROM);
    }
}