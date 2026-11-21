package org.ajeet.device;

import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

/**
 * JNA mapping of the vendor's SBXPC SDK (SBXPCDLL64.dll).
 * Signatures mirror the DllImport declarations in the SDK's C# sample
 * (sbxpc/SBXPCDLL.cs): byte returns, IntPtr out-params, and exports carry a
 * leading "_" (added via FunctionMapper in {@link DeviceClient}).
 * Loaded lazily by {@link DeviceClient} so jna.library.path can be set first.
 */
public interface Sbxpc extends StdCallLibrary {

    /**
     * Opens a TCP session. ipPtr must be a void** pointing to a UTF-16LE
     * string buffer — the C# sample marshals it as a BSTR
     * (Marshal.StringToBSTR + ref IntPtr).
     */
    byte ConnectTcpip(int machine, PointerByReference ipPtr, int port, int password);

    /** Closes the session opened by ConnectTcpip. */
    void Disconnect(int machine);

    /** Locks (0) or unlocks (1) the device's keypad/UI while syncing. */
    byte EnableDevice(int machine, byte flag);

    /**
     * Loads the general attendance log into the read buffer.
     * readMark: 1 marks records as read on the device; 0 leaves them unmarked.
     */
    byte ReadGeneralLogData(int machine, byte readMark);

    /**
     * Reads one log record per call; 0 when the buffer is drained or on error.
     * All IntByReference out-params are filled on success.
     */
    byte GetGeneralLogData(int machine,
            IntByReference tmMachine, IntByReference enrollNumber,
            IntByReference eMachine, IntByReference verifyMode,
            IntByReference year, IntByReference month, IntByReference day,
            IntByReference hour, IntByReference minute, IntByReference second);

    /** Loads the enrolled-user list into the read buffer. */
    byte ReadAllUserID(int machine);

    /**
     * Reads one enrolled user per call; 0 when the buffer is drained.
     * backupNumber: 0-9 = fingerprint index, 10 = password enroll.
     */
    byte GetAllUserID(int machine,
            IntByReference enrollNumber, IntByReference eMachine,
            IntByReference backupNumber, IntByReference privilege,
            IntByReference enable);

    /**
     * Retrieves a user's name by enroll number. namePtr receives a pointer
     * to a DLL-allocated UTF-16LE string (Marshal.PtrToStringUni in C#).
     */
    byte GetUserName1(int machine, int enrollNumber, PointerByReference namePtr);

    /** Clears the general log on the device (kept for completeness; unused). */
    byte EmptyGeneralLogData(int machine);

    /** Reads a device status counter (index meanings: see manual). */
    byte GetDeviceStatus(int machine, int statusIndex, IntByReference value);

    /** Retrieves the error code of the last failed call on that machine. */
    byte GetLastError(int machine, IntByReference error);
}
