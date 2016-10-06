package com.google.brillo.driver.button;

import android.hardware.pio.Gpio;
import android.hardware.pio.GpioCallback;
import android.hardware.pio.PeripheralManagerService;
import android.hardware.userdriver.InputDriver;
import android.hardware.userdriver.InputDriverEvent;
import android.system.ErrnoException;
import android.util.Log;

import java.io.Closeable;
import java.util.HashMap;
import java.util.Map;

/**
 * Driver for GPIO based buttons with pull-up or pull-down resistors.
 */
@SuppressWarnings("WeakerAccess")
public class Button implements Closeable {
    private static final String TAG = Button.class.getSimpleName();

    /**
     * Logic level when the button is considered pressed.
     */
    public enum LogicState {
        PRESSED_WHEN_HIGH,
        PRESSED_WHEN_LOW
    }
    private Gpio mButtonGpio;
    private GpioCallback mInterruptCallback;
    private OnButtonEventListener mListener;

    /**
     * Interface definition for a callback to be invoked when Button event occur.
     */
    public interface OnButtonEventListener {
        boolean onButtonEvent(boolean pressed);
    }

    /**
     * Create a new Button driver for the givin GPIO pin name.
     * @param pin Gpio where the button is attached.
     * @param logicLevel Logic level when the button is considered pressed.
     * @throws ErrnoException
     */
    public Button(String pin, LogicState logicLevel) throws ErrnoException {
        PeripheralManagerService pioService = new PeripheralManagerService();
        Gpio buttonGpio = pioService.openGpio(pin);
        connect(buttonGpio, logicLevel);
    }

    /**
     * Create a new Button driver for the given Gpio connection.
     * @param buttonGpio Gpio where the button is attached.
     * @param logicLevel Logic level when the button is considered pressed.
     * @throws ErrnoException
     */
    public Button(Gpio buttonGpio, LogicState logicLevel) throws ErrnoException {
       connect(buttonGpio, logicLevel);
    }

    private void connect(Gpio buttonGpio, LogicState logicLevel) throws ErrnoException {
        mButtonGpio = buttonGpio;
        mButtonGpio.setDirection(Gpio.DIRECTION_IN);
        mButtonGpio.setEdgeTriggerType(Gpio.EDGE_BOTH);
        mInterruptCallback = new GpioCallback() {
            @Override
            public boolean onGpioEdge(Gpio gpio) {
                if (mListener == null) {
                    return true;
                }
                try {
                    boolean state = gpio.getValue();
                    if (logicLevel == LogicState.PRESSED_WHEN_HIGH) {
                        return mListener.onButtonEvent(state);

                    }
                    if (logicLevel == LogicState.PRESSED_WHEN_LOW) {
                        return mListener.onButtonEvent(!state);
                    }
                } catch (ErrnoException e) {
                    Log.e(TAG, "pio error: ", e);
                }
                return true;
            }
        };
        mButtonGpio.registerGpioCallback(mInterruptCallback);
    }

    /**
     * Set the listener to be called when a button event occured.
     * @param listener button event listener to be invoked.
     */
    public void setOnButtonEventListener(OnButtonEventListener listener) {
        mListener = listener;
    }

    /**
     * Close the driver and the underlying device.
     */
    @Override
    public void close() {
        if (mButtonGpio != null) {
            mListener = null;
            mButtonGpio.unregisterGpioCallback(mInterruptCallback);
            mInterruptCallback = null;
            mButtonGpio.close();
            mButtonGpio = null;
        }
    }

    static class ButtonInputDriver {
        private static final int EV_SYN = 0;
        private static final int EV_KEY = 1;
        private static final int BUTTON_RELEASED = 0;
        private static final int BUTTON_PRESSED = 1;
        private static Integer[] SUPPORTED_EVENT_TYPE = {EV_SYN, EV_KEY};
        static InputDriver build(Button button, int key) {
            Map<Integer, Integer[]> supportedKey = new HashMap<>();
            supportedKey.put(key, SUPPORTED_EVENT_TYPE);
            InputDriver inputDriver = InputDriver.builder(supportedKey).build();
            button.setOnButtonEventListener(new OnButtonEventListener() {
                @Override
                public boolean onButtonEvent(boolean pressed) {
                    int keyState = pressed ? BUTTON_PRESSED : BUTTON_RELEASED;
                    inputDriver.emit(new InputDriverEvent[]{
                            new InputDriverEvent(EV_KEY, key, keyState),
                            new InputDriverEvent(EV_SYN, 0, 0)
                    });
                    return true;
                }
            });
            return inputDriver;
        }
    }

    /**
     * Create a new {@link android.hardware.userdriver.InputDriver} that will emit
     * the proper key events whenever the {@link Button} is pressed or released.
     * Register this driver with the framework by calling {@link android.hardware.userdriver.UserDriverManager#registerInputDriver(InputDriver)}
     * @param key key to be emitted.
     * @return new input driver instance.
     * @see android.hardware.userdriver.UserDriverManager#registerInputDriver(InputDriver)
     */
    public InputDriver createInputDriver(int key) {
        return ButtonInputDriver.build(this, key);
    }
}