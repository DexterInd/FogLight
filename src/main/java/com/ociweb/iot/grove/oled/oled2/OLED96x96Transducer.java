package com.ociweb.iot.grove.oled.oled2;

import com.ociweb.gl.api.transducer.StartupListenerTransducer;
import com.ociweb.iot.grove.oled.BinaryOLED;
import com.ociweb.iot.maker.FogCommandChannel;
import com.ociweb.iot.maker.IODeviceTransducer;
import com.ociweb.iot.maker.image.*;
import com.ociweb.pronghorn.iot.schema.I2CCommandSchema;
import com.ociweb.pronghorn.pipe.DataOutputBlobWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OLED96x96Transducer implements IODeviceTransducer, StartupListenerTransducer, FogBmpDisplayable {
    private Logger logger = LoggerFactory.getLogger((BinaryOLED.class));
    private final FogCommandChannel ch;
    private final int i2cCommandBatchLimit = 64; // Max commands before we must call flush (batch is i2c bus atomic, and must fit entirely on channel)
    private final int i2cCommandMaxSize = 64; // Max single i2C command size in bytes (i.e. address, register, payload)
    // TODO another constant for data payload chunks. This effects i2cCommandMaxSize.

    private static final int REMAP_SGMT = 0xA0;

    // Initialization

    public OLED96x96Transducer(FogCommandChannel ch) {
        this.ch = ch;
        // TODO: commandCountCapacity cannot be just i2cCommandBatchLimit, why?
        this.ch.ensureI2CWriting(i2cCommandBatchLimit * i2cCommandMaxSize, i2cCommandMaxSize);
    }

    @Override
    public void startup() {
        if (!init()) {
            logger.error("Failed to init.");
        }
        if (!setOrientation(OLEDOrientation.horizontal)) {
            logger.error("Failed to set horizontal.");
        }
        if (!turnScreenOn()) {
            logger.error("Failed to turn screen on.");
        }
    }

    private boolean init() {
        if (tryBeginI2CBatch(1)) {
            final int DISPLAY_OFF =  0xAE;
            final int SET_D_CLOCK = 0xD5;
            final int SET_ROW_ADDRESS = 0x20;
            final int SET_CONTRAST = 0x81;
            final int NORMAL_DISPLAY = 0xA6;
            final int SET_EXT_VPP = 0xAD;
            final int SET_COMMON_SCAN_DIR = 0xC0;
            final int SET_PHASE_LENGTH = 0xD9;
            final int SET_VCOMH_VOLTAGE = 0xDB;
            final int ENTIRE_DISPLAY_ON = 0xA4;

            DataOutputBlobWriter<I2CCommandSchema> writer = beginI2CCommand();
            queueInstruction(writer, DISPLAY_OFF);
            queueInstruction(writer, SET_D_CLOCK);
            queueInstruction(writer, 0x50);
            queueInstruction(writer, SET_ROW_ADDRESS);
            queueInstruction(writer, SET_CONTRAST);
            queueInstruction(writer, 0x70);
            queueInstruction(writer, REMAP_SGMT);
            queueInstruction(writer, ENTIRE_DISPLAY_ON);
            queueInstruction(writer, NORMAL_DISPLAY);
            queueInstruction(writer, SET_EXT_VPP);
            queueInstruction(writer, 0x80);
            queueInstruction(writer, SET_COMMON_SCAN_DIR);
            queueInstruction(writer, SET_PHASE_LENGTH);
            queueInstruction(writer, 0x1F);
            queueInstruction(writer, SET_VCOMH_VOLTAGE);
            queueInstruction(writer, 0x20);
            endI2CCommand(writer);
            endI2CBatch();
            return true;
        }
        return false;
    }

    private boolean turnScreenOn() {
        final int DISPLAY_ON = 0xAF;
        if (tryBeginI2CBatch(1)) {
            DataOutputBlobWriter<I2CCommandSchema> writer = beginI2CCommand();
            queueInstruction(writer, DISPLAY_ON);
            endI2CCommand(writer);
            endI2CBatch();
            return true;
        }
        return false;
    }

    // Public API

    public boolean clearDisplay() {
        // TODO
        return true;
    }

    public boolean setContrast(byte contrast) {
        final int SET_CONTRAST = 0x81;
        if (tryBeginI2CBatch(1)) {
            DataOutputBlobWriter<I2CCommandSchema> writer = beginI2CCommand();
            queueInstruction(writer, SET_CONTRAST);
            queueInstruction(writer, contrast);
            endI2CCommand(writer);
            endI2CBatch();
            return true;
        }
        return false;
    }

    public boolean setOrientation(OLEDOrientation orientation) {
        if (tryBeginI2CBatch(1)) {
            DataOutputBlobWriter<I2CCommandSchema> writer = beginI2CCommand();
            queueInstruction(writer, REMAP_SGMT);
            queueInstruction(writer, orientation.COMMAND);
            endI2CCommand(writer);
            endI2CBatch();
            return true;
        }
        return false;
    }

    public boolean setScrollActivated(boolean activated) {
        final int SET_ACTIVATED = activated ? 0x2F : 0x2E;
        if (tryBeginI2CBatch(1)) {
            DataOutputBlobWriter<I2CCommandSchema> writer = beginI2CCommand();
            queueInstruction(writer, SET_ACTIVATED);
            endI2CCommand(writer);
            endI2CBatch();
            return true;
        }
        return false;
    }

    public boolean setHorizontalScrollProperties(OLEDScrollDirection direction, int startRow, int endRow, int startColumn, int endColumn, OLEDScrollSpeed speed) {
        if (tryBeginI2CBatch(1)) {
            DataOutputBlobWriter<I2CCommandSchema> writer = beginI2CCommand();
            queueInstruction(writer, direction.COMMAND);
            queueInstruction(writer, 0x00);
            queueInstruction(writer, startRow);
            queueInstruction(writer, speed.COMMAND);
            queueInstruction(writer, endRow);
            queueInstruction(writer, startColumn+8);
            queueInstruction(writer, endColumn+8);
            queueInstruction(writer, 0x00);
            endI2CCommand(writer);
            endI2CBatch();
            return true;
        }
        return false;
    }

    public boolean setPresentation(OLEDScreenPresentation presentation) {
        if (tryBeginI2CBatch(1)) {
            DataOutputBlobWriter<I2CCommandSchema> writer = beginI2CCommand();
            queueInstruction(writer, presentation.COMMAND);
            endI2CCommand(writer);
            endI2CBatch();
            return true;
        }
        return false;
    }

    // FogBitmap

    @Override
    public FogBitmapLayout newBmpLayout() {
        FogBitmapLayout bmpLayout = new FogBitmapLayout(FogColorSpace.gray);
        bmpLayout.setComponentDepth((byte) 4);
        bmpLayout.setWidth(96);
        bmpLayout.setHeight(96);
        return bmpLayout;
    }

    @Override
    public FogBitmap newEmptyBmp() {
        return new FogBitmap(newBmpLayout());
    }

    @Override
    public FogPixelScanner newPreferredBmpScanner(FogBitmap bmp) { return new FogPixelProgressiveScanner(bmp); }

    @Override
    public boolean display(FogPixelScanner scanner) {
        return testScreen();
    }

    // Commands

    private boolean tryBeginI2CBatch(int commandCount) {
        if (!ch.i2cIsReady(commandCount)) {
            logger.trace("I2C is not ready for batch of {} commands.", commandCount);
            return false;
        }
        return true;
    }

    private DataOutputBlobWriter<I2CCommandSchema> beginI2CCommand() {
        final int i2cAddress = 0x3c;
        return ch.i2cCommandOpen(i2cAddress);
    }

    private void queueInstruction(DataOutputBlobWriter<I2CCommandSchema> writer, int data) {
        writer.write(OLEDMode.instruction.COMMAND);
        writer.write(data & 0xFF);
    }

    private void queueData(DataOutputBlobWriter<I2CCommandSchema> writer, int data) {
        writer.write(OLEDMode.data.COMMAND);
        writer.write(data & 0xFF);
    }

    private void endI2CCommand(DataOutputBlobWriter<I2CCommandSchema> writer) {
        if (ch.i2cCommandClose(writer) > i2cCommandMaxSize) {
            throw new UnsupportedOperationException("Write too large i2C command. i2cCommandMaxSize is too small or too much was written.");
        }
    }

    private void endI2CBatch() {
        ch.i2cFlushBatch();
    }

    // Test

    private boolean testScreen() {

        System.out.print("injectInitScreen2\n");
        setOrientation(OLEDOrientation.horizontal);

        final int SET_ROW_BASE_BYTE = 0xB0;
        int Row = 0;
        int column_l = 0x00;
        int column_h = 0x11; // 17
        boolean ended = false;

        for(int i=0;i<(96*96/8);i++) //1152
        {
            if (((i + i2cCommandBatchLimit) % i2cCommandBatchLimit) == 0) {
                if (!tryBeginI2CBatch(i2cCommandBatchLimit)) {
                    return false;
                }
            }
            ended = false;

            int tmp = 0x00;
            for(int b = 0; b < 8; b++)
            {
                int bits = SeeedLogo[i];
                tmp |= ( ( bits >> ( 7 - b ) ) & 0x01 ) << b;
            }
            //tmp = 0x0F;
            //tmp = 0xF0;
            //tmp = 0x00;
            //tmp = 0xFF;
            //tmp = 0x88;

            DataOutputBlobWriter<I2CCommandSchema> writer = beginI2CCommand();
            queueInstruction(writer, SET_ROW_BASE_BYTE + Row);
            queueInstruction(writer, column_l);
            queueInstruction(writer, column_h);
            queueData(writer, tmp);
            endI2CCommand(writer);

            Row++;

            if(Row >= 12){
                Row = 0;
                column_l++;
                //logger.info("End Row {}", column_l);
                if(column_l >= 16){
                    column_l = 0x00;
                    column_h += 0x01;
                    //logger.info("End Col {}", column_h);
                }
            }

            if (((i + i2cCommandBatchLimit) % i2cCommandBatchLimit) == i2cCommandBatchLimit - 1) {
                endI2CBatch();
                ended = true;
            }

        }
        if (!ended) {
            endI2CBatch();
        }
        return true;
    }

    final int[] SeeedLogo = new int[] { // 16 * 72 == 1152,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x60, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xC0, 0x06, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0xC0, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x01, 0xC0, 0x07, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x03, 0x80, 0x03, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0x80, 0x03, 0x80,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, 0x80, 0x03, 0xC0, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x07, 0x80, 0x01, 0xC0, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20,
            0x07, 0x80, 0x01, 0xE0, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x0F, 0x80, 0x01, 0xE0,
            0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x30, 0x0F, 0x00, 0x01, 0xE0, 0x08, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x30, 0x0F, 0x00, 0x01, 0xE0, 0x18, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x30,
            0x0F, 0x00, 0x01, 0xE0, 0x18, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x30, 0x0F, 0x00, 0x01, 0xE0,
            0x18, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x38, 0x0F, 0x00, 0x01, 0xE0, 0x18, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x38, 0x0F, 0x00, 0x01, 0xE0, 0x38, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x38,
            0x0F, 0x80, 0x01, 0xE0, 0x38, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x3C, 0x0F, 0x80, 0x01, 0xE0,
            0x78, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x3E, 0x0F, 0x80, 0x03, 0xE0, 0x78, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x1E, 0x07, 0x80, 0x03, 0xE0, 0xF8, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1E,
            0x07, 0x80, 0x03, 0xE0, 0xF0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x1F, 0x07, 0x80, 0x03, 0xC1,
            0xF0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0F, 0x87, 0xC0, 0x07, 0xC1, 0xF0, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x0F, 0x83, 0xC0, 0x07, 0x83, 0xE0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0F,
            0xC3, 0xC0, 0x07, 0x87, 0xE0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x07, 0xE1, 0xE0, 0x07, 0x0F,
            0xC0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0xF0, 0xE0, 0x0F, 0x0F, 0x80, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x01, 0xF8, 0xF0, 0x0E, 0x1F, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
            0xF8, 0x70, 0x1C, 0x3F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFC, 0x30, 0x18, 0x7E,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7F, 0x18, 0x30, 0xFC, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x1F, 0x88, 0x21, 0xF0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x0F, 0xC4, 0x47, 0xE0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, 0xE0, 0x0F, 0x80,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xF8, 0x3E, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x0E, 0xE0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x6C, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x02, 0x00, 0x06, 0x00, 0x00, 0x6C, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x06,
            0x00, 0x00, 0x60, 0x00, 0x7E, 0x3F, 0x0F, 0xC3, 0xF0, 0xFA, 0x0F, 0xDF, 0xE1, 0x9F, 0xEC, 0x7E,
            0xE6, 0x73, 0x9C, 0xE7, 0x39, 0xCE, 0x1C, 0xDF, 0xE1, 0xB9, 0xEC, 0xE7, 0xE0, 0x61, 0xD8, 0x66,
            0x1B, 0x86, 0x1C, 0x06, 0x61, 0xB0, 0x6D, 0xC3, 0x7C, 0x7F, 0xFF, 0xFF, 0xFF, 0x06, 0x0F, 0x86,
            0x61, 0xB0, 0x6D, 0x83, 0x3E, 0x7F, 0xFF, 0xFF, 0xFF, 0x06, 0x07, 0xC6, 0x61, 0xB0, 0x6D, 0x83,
            0xC3, 0x61, 0x18, 0x46, 0x03, 0x86, 0x18, 0x66, 0x61, 0xB0, 0x6D, 0xC3, 0xFE, 0x7F, 0x9F, 0xE7,
            0xF9, 0xFE, 0x1F, 0xE6, 0x3F, 0x9F, 0xEC, 0xFE, 0x7E, 0x3F, 0x0F, 0xC3, 0xF0, 0xFA, 0x0F, 0xC6,
            0x3F, 0x9F, 0xEC, 0x7E, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7C, 0x00,
            0x00, 0x20, 0x82, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x44, 0x00, 0x00, 0x20, 0x82, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x6C, 0xF3, 0xCF, 0x70, 0x9E, 0x79, 0xE7, 0x80, 0x00, 0x00,
            0x00, 0x00, 0x7D, 0x9E, 0x68, 0x20, 0xB2, 0xC8, 0x64, 0x00, 0x00, 0x00, 0x00, 0x00, 0x47, 0x9E,
            0x6F, 0x20, 0xB2, 0xF9, 0xE7, 0x80, 0x00, 0x00, 0x00, 0x00, 0x46, 0x9A, 0x61, 0x20, 0xB2, 0xCB,
            0x60, 0x80, 0x00, 0x00, 0x00, 0x00, 0x7C, 0xF3, 0xCF, 0x30, 0x9E, 0x79, 0xE7, 0x90, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x7C, 0x02, 0x00, 0x00, 0x82, 0x60, 0x00, 0x00,
            0xF8, 0x00, 0x00, 0x40, 0x40, 0x02, 0x00, 0x00, 0x83, 0x60, 0x00, 0x00, 0x8C, 0x00, 0x00, 0x40,
            0x60, 0xB7, 0x79, 0xE7, 0x81, 0xC7, 0x92, 0x70, 0x89, 0xE7, 0x9E, 0x78, 0x7C, 0xE2, 0xC9, 0x2C,
            0x81, 0xCC, 0xD2, 0x40, 0xFB, 0x21, 0xB2, 0x48, 0x40, 0x62, 0xF9, 0x2C, 0x80, 0x8C, 0xD2, 0x40,
            0x8B, 0xE7, 0xB0, 0x48, 0x40, 0xE2, 0xC9, 0x2C, 0x80, 0x84, 0xD2, 0x40, 0x8B, 0x2D, 0x92, 0x48,
            0x7D, 0xB3, 0x79, 0x27, 0x80, 0x87, 0x9E, 0x40, 0x8D, 0xE7, 0x9E, 0x48, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    };
}
