package com.jjurm.libs.charlcd;

import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.Pin;
import com.pi4j.io.gpio.PinState;

/**
 * @author JJurM
 */
public class CharLCD {
	
	public static enum MODE {
		COMMAND (0),
		DATA (1);
		
		public final int value;
		MODE (int value) { this.value = value; }
	}

	public static enum CMD {
		CLEARDISPLAY (0x01),
		RETURNHOME (0x02),
		ENTRYMODESET (0x04),
		DISPLAYCONTROL (0x08),
		CURSORSHIFT (0x10),
		FUNCTIONSET (0x20),
		SETCGRAMADDR (0x40),
		SETDDRAMADDR (0x80);
		
		final int bits;
		CMD (int bits) { this.bits = bits; }
	}
	
	public static enum SHIFTED {
		TRUE (1),
		FALSE(0);
		
		final int value;
		SHIFTED (int value) { this.value = value; }
	}

	public static enum CURSORMOVEMENT {
		INCREASE (1),
		DECREASE (0);
		
		final int value;
		CURSORMOVEMENT (int value) { this.value = value; }
	}
	
	public static enum BLINKING {
		ON (1),
		OFF (0);
		
		final int value;
		BLINKING (int value) { this.value = value; }
	}
	
	public static enum CURSOR {
		ON (1),
		OFF (0);
		
		final int value;
		CURSOR (int value) { this.value = value; }
	}
	
	public static enum DISPLAY {
		ON (1),
		OFF (0);
		
		final int value;
		DISPLAY (int value) { this.value = value; }
	}
	
	public static enum SHIFTDIRECTION {
		RIGHT (1),
		LEFT (0);
		
		final int value;
		SHIFTDIRECTION (int value) { this.value = value; }
	}

	public static enum SHIFT {
		DISPLAYSHIFT (1),
		CURSORMOVE (0);
		
		final int value;
		SHIFT (int value) { this.value = value; }
	}
	
	public static enum DOTSIZE {
		DOTS_5x10 (1), // or 5x11
		DOTS_5x7 (0); // or 5x8
		
		final int value;
		DOTSIZE (int value) { this.value = value; }
	}
	
	public static enum MULTILINE {
		MULTI (1),
		SINGLE (0);
		
		final int value;
		MULTILINE (int value) { this.value = value; }
	}
	
	public static enum BITMODE {
		MODE_8BIT (1),
		MODE_4BIT (0);
		
		final int value;
		BITMODE (int value) { this.value = value; }
	}

	// === Fields ===
	// Entry mode
	private SHIFTED shifted = SHIFTED.FALSE;
	private CURSORMOVEMENT cursorMovement = CURSORMOVEMENT.INCREASE;

	// Display control
	private BLINKING blinking = BLINKING.OFF;
	private CURSOR cursor = CURSOR.OFF;
	private DISPLAY display = DISPLAY.ON;

	// Function set
	private DOTSIZE dotsize;
	private MULTILINE multiline;
	private BITMODE bitmode;
	
	// Pins
	private Pin pin_rs;
	private Pin pin_e;
	private Pin[] pins_db;
	private Pin pin_backlight;
	
	private int cols;
	private int rows;
	
	// Outputs
	private GpioPinDigitalOutput out_rs;
	private GpioPinDigitalOutput out_e;
	private GpioPinDigitalOutput[] outs_db;
	private GpioPinDigitalOutput out_backlight = null;
	
	public CharLCD(Pin pin_rs, Pin pin_e, Pin[] pins_db, Pin pin_backlight, int cols, int rows, DOTSIZE dotsize) {
		this.pin_rs = pin_rs;
		this.pin_e = pin_e;
		this.pins_db = pins_db;
		this.pin_backlight = pin_backlight;
		
		switch (this.pins_db.length) {
		case 8:
			bitmode = BITMODE.MODE_8BIT;
			break;
		case 4:
			bitmode = BITMODE.MODE_4BIT;
			break;
		default:
			throw new IllegalArgumentException(String.format("Length of pins_db is %d, expecting 8 or 4", this.pins_db.length));
		}
		
		this.cols = cols;
		this.rows = rows;
		this.multiline = (rows == 1) ? MULTILINE.SINGLE : MULTILINE.MULTI;
		this.dotsize = dotsize;

		setupPins();
		initialize();
	}
	
	public CharLCD(Pin pin_rs, Pin pin_e, Pin[] pins_db) {
		this(pin_rs, pin_e, pins_db, null, 16, 2, DOTSIZE.DOTS_5x7);
	}
	
	private void setupPins() {
		final GpioController gpio = GpioFactory.getInstance();
		out_rs = gpio.provisionDigitalOutputPin(pin_rs, PinState.LOW);
		out_e = gpio.provisionDigitalOutputPin(pin_e, PinState.LOW);
		outs_db = new GpioPinDigitalOutput[pins_db.length];
		for (int i = 0; i < pins_db.length; i++) {
			outs_db[i] = gpio.provisionDigitalOutputPin(pins_db[i], PinState.LOW);
		}
		if (pin_backlight != null) {
			out_backlight = gpio.provisionDigitalOutputPin(pin_backlight, PinState.LOW);
		}
	}
	
	private void initialize() {
		switch (bitmode) {
		case MODE_8BIT:
			
			// initialization sequence of 3 function set commands
			pushFunctionSet();
			sleep(5);  // wait > 4.1ms
			
			// second attempt
			pushFunctionSet();
			busyWait(150);  // wait > 100us
			
			// third attempt
			pushFunctionSet();
			
			break;
		case MODE_4BIT:
			
			// initialization starts in 8bit mode
			write4bits(0x03);
			sleep(5);  // wait > 4.1ms
			
			// second attempt
			write4bits(0x03);
			busyWait(150);  // wait > 100us
			
			// third attempt
			write4bits(0x03);
			
			// proceed to 4bit communication
			write4bits(0x02);
			
			break;
		}
		
		// finally start configuration
		pushFunctionSet();
		pushDisplayControl();
		clear();
		pushEntryMode();
	}
	
	/**
	 * Puts currently executing thread to sleep for a specified number of milliseconds.
	 * 
	 * @param ms milliseconds
	 */
	private void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			// Restore the interrupted status
			Thread.currentThread().interrupt();
		}
	}
	
	/**
	 * Performs busy wait for the specified number of microseconds.
	 * 
	 * @param us microseconds
	 */
	private void busyWait(int us) {
		long nanos = us * 1000;
		long start = System.nanoTime();
		while (start + nanos >= System.nanoTime());
	}
	
	/**
	 * Sends one enable pulse.
	 */
	private void pulseEnable() {
		out_e.setState(PinState.LOW);
		busyWait(1);  // enable pulse must be > 450ns
		out_e.setState(PinState.HIGH);
		busyWait(1);  // enable pulse must be > 450ns
		out_e.setState(PinState.LOW);
		busyWait(100); // commands need > 37us to settle
	}
	
	/**
	 * Extracts specified number of bits from value, writes them to the last
	 * pins of {@link #pins_db} and sends enable pulse.
	 * 
	 * @param bits number of bits to use
	 * @param value
	 */
	private void writeBits(int bits, int value) {
		for (int i = 0; i < bits; i++) {
			outs_db[pins_db.length - bits + i].setState(((value >> i) & 0x01) == 1);
		}
		pulseEnable();
	}
	private void write8bits(int value) { writeBits(8, value); }
	private void write4bits(int value) { writeBits(4, value); }
	
	/**
	 * Writes value with given mode (either command or data), with auto 4/8-bit
	 * selection.
	 * 
	 * @param value
	 * @param mode
	 */
	private void send(int value, MODE mode) {
		out_rs.setState(mode.value == 1);
		switch (bitmode) {
		case MODE_8BIT:
			write8bits(value & 0xFF);
			break;
		case MODE_4BIT:
			write4bits((value >> 4) & 0xF);
			write4bits(value & 0xF);
			break;
		}
	}
	
	/**
	 * Sends value as command.
	 * 
	 * @param value
	 */
	public void command(int value) {
		send(value, MODE.COMMAND);
	}
	
	/**
	 * Sends value as data.
	 * 
	 * @param value
	 */
	public void data(int value) {
		send(value, MODE.DATA);
	}
	
	private void pushEntryMode() {
		command(CMD.ENTRYMODESET.bits
				| (0x01 * shifted.value)
				| (0x02 * cursorMovement.value)
		);
	}
	
	private void pushDisplayControl() {
		command(CMD.DISPLAYCONTROL.bits
				| (0x01 * blinking.value)
				| (0x02 * cursor.value)
				| (0x04 * display.value)
		);
	}
	
	private void pushFunctionSet() {
		command(CMD.FUNCTIONSET.bits
				| (0x04 * dotsize.value)
				| (0x08 * multiline.value)
				| (0x10 * bitmode.value)
		);
	}
	
	/**
	 * Clears display and returns cursor home.
	 */
	public void clear() {
		command(CMD.CLEARDISPLAY.bits);
	}
	
	/**
	 * Returns cursor home.
	 */
	public void home() {
		command(CMD.RETURNHOME.bits);
	}
	
	/**
	 * Moves cursor to the specified position
	 * 
	 * @param col column (numbering from 0)
	 * @param row row (numbering from 0)
	 */
	public void moveCursor(int col, int row) {
		if (col < 0 || col >= cols || row < 0 || row >= rows) {
			throw new IllegalArgumentException(String.format("Requested cursor position (%d, %d) is out of range", col, row));
		}
		// row offsets are [0x00, 0x40, 0x00 + cols, 0x40 + cols]
		int addr = ((row & 0x01) * 0x40 + (row & 0x02) * cols) + cols;
		command(CMD.SETDDRAMADDR.bits
				| addr
		);
	}

	/**
	 * Shifts cursor given number of characters to the left. In case of the
	 * count being negative, cursor will be shifted to the right. When
	 * {@code shiftDisplay} is {@code true}, also shifts the display.
	 * 
	 * @param count
	 * @param shiftDisplay
	 */
	public void shift(int count, boolean shiftDisplay) {
		if (count == 0) return;
		SHIFTDIRECTION direction = (count > 0) ? SHIFTDIRECTION.LEFT : SHIFTDIRECTION.RIGHT;
		SHIFT shift = shiftDisplay ? SHIFT.DISPLAYSHIFT : SHIFT.CURSORMOVE;
		for (int i = Math.abs(count); i > 0; i--) {
			command(CMD.CURSORSHIFT.bits
					| (0x04 * direction.value)
					| (0x08 * shift.value)
			);
		}
	}
	
	/**
	 * When enabled, display will be shifted after each data operation.
	 * 
	 * @param shifted
	 */
	public void setShifted(SHIFTED shifted) {
		this.shifted = shifted;
		pushEntryMode();
	}
	
	/**
	 * Set direction to move the cursor after each data operation.
	 * 
	 * @param cursorMovement
	 */
	public void setCursorMovement(CURSORMOVEMENT cursorMovement) {
		this.cursorMovement = cursorMovement;
		pushEntryMode();
	}
	
	/**
	 * Turns blinking of cursor on or off.
	 * 
	 * @param blinking
	 */
	public void setBlinking(BLINKING blinking) {
		this.blinking = blinking;
		pushDisplayControl();
	}
	
	/**
	 * Turns cursor pattern on or off.
	 * 
	 * @param cursor
	 */
	public void setCursor(CURSOR cursor) {
		this.cursor = cursor;
		pushDisplayControl();
	}
	
	/**
	 * Turns display on or off.
	 * 
	 * @param display
	 */
	public void setDisplay(DISPLAY display) {
		this.display = display;
		pushDisplayControl();
	}
	
	/**
	 * Creates custom character at specified address (0-7), using given bytemap
	 * for mapping bits to pixels.
	 * 
	 * @param addr
	 * @param bytemap
	 */
	public void createChar(int addr, byte[] bytemap) {
		if (bytemap.length != 8) {
			throw new IllegalArgumentException("Character bytemap must have 8 bytes");
		}
		addr &= 0x7;
		command(CMD.SETCGRAMADDR.bits
				| (addr << 3) // each character occupies 2^3 bytes
		);
		for (int i = 0; i < 8; i++) {
			data(bytemap[i]);
		}
	}
	
	/**
	 * Writes string char by char.
	 * 
	 * @param string
	 */
	public void write(String string) {
		for (char ch : string.toCharArray()) {
			data(ch);
		}
	}

	/**
	 * Writes string to specified row and clears remaining columns in the row.
	 * 
	 * @param row
	 * @param string
	 */
	public void wline(int row, String string) {
		moveCursor(0, row);
		write(string);
		for (int c = cols - string.length(); c > 0; c--) {
			data(' ');
		}
	}
	
	/**
	 * Turns display backlight on or off.
	 * 
	 * @param on
	 */
	public void setBacklight(boolean on) {
		if (out_backlight != null) {
			out_backlight.setState(on);
		}
	}
	
}
