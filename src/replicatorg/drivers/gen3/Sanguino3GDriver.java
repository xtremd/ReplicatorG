/*
 Sanguino3GDriver.java

 This is a driver to control a machine that uses the Sanguino with 3rd Generation Electronics.

 Part of the ReplicatorG project - http://www.replicat.org
 Copyright (c) 2008 Zach Smith

 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 2 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software Foundation,
 Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package replicatorg.drivers.gen3;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Vector;
import java.util.logging.Level;

import javax.vecmath.Point3d;

import org.w3c.dom.Node;

import replicatorg.app.Base;
import replicatorg.drivers.BadFirmwareVersionException;
import replicatorg.drivers.MultiTool;
import replicatorg.drivers.OnboardParameters;
import replicatorg.drivers.PenPlotter;
import replicatorg.drivers.RetryException;
import replicatorg.drivers.SDCardCapture;
import replicatorg.drivers.SerialDriver;
import replicatorg.drivers.Version;
import replicatorg.drivers.gen3.PacketProcessor.CRCException;
import replicatorg.machine.model.Axis;
import replicatorg.machine.model.ToolModel;
import replicatorg.uploader.FirmwareUploader;

public class Sanguino3GDriver extends SerialDriver
	implements OnboardParameters, SDCardCapture, PenPlotter, MultiTool
{
	protected final static int DEFAULT_RETRIES = 5;
	
	Version toolVersion = new Version(0,0);
	
	public Sanguino3GDriver() {
		super();

		// This driver handles v1.X and v2.X firmware
		minimumVersion = new Version(1,1);
		preferredVersion = new Version(2,0);
		// init our variables.
		setInitialized(false);
	}

	public void loadXML(Node xml) {
		super.loadXML(xml);

	}

	public void initialize() {
		// Assert: serial port present.
		assert serial != null : "No serial port found.";
		// wait till we're initialized
		if (!isInitialized()) {
			// attempt to send version command and retrieve reply.
			try {
				// Default timeout should be 2.6s.  Timeout can be sped up for v2, but let's play it safe.
				int timeout = 2600;
				connectToDevice(timeout);
			} catch (Exception e) {
				// todo: handle init exceptions here
				e.printStackTrace();
			}
		}

		// did it actually work?
		if (isInitialized()) {
			// okay, take care of version info /etc.
			if (version.compareTo(getMinimumVersion()) < 0) {
				throw new BadFirmwareVersionException(version,getMinimumVersion());
			}
			sendInit();
			super.initialize();
			invalidatePosition();

			return;
		} else {
			Base.logger.log(Level.INFO,"Unable to connect to firmware.");
			// Dispose of driver to free up any resources
			dispose();
		}
	}

	private boolean attemptConnection() {
		// Eat anything in the serial buffer
		serial.clear();
		version = getVersionInternal();
		if (getVersion() != null)
			setInitialized(true);
		return isInitialized();
	}
	
	/**
	 * Connect to the device. After the specified timeout, replicatorG will
	 * attempt to remotely reset the device.
	 * 
	 * @timeoutMillis the time, in milliseconds, that we should wait for a
	 *                handshake.
	 * @return true if we received a handshake; false if we timed out.
	 */
	protected void connectToDevice(int timeoutMillis) {
		assert (serial != null);
		synchronized (serial) {
			serial.clear();
			serial.setTimeout(timeoutMillis);
			if (attemptConnection()) return;
			// Timed out waiting.  It's possible that a reset was triggered when the device
			// was opened, since RXTX doesn't allow control of default RTS states.
			// Wait >2.6s -- 2s for the arduino reset; .6 seconds for the rest of the
			// system to come up.
			try {
				Thread.sleep(2600);
			} catch (InterruptedException ie) {
				// Assume we're shutting down the app or aborting the
				// attempt.  Reassert interrupted status and let
				// the thread wind down.
				Thread.currentThread().interrupt();
				return;
			}
			if (attemptConnection()) return;
			// Timed out again.  It is possible that the machine is in a bad state.
			Base.logger.warning("No connection; trying to pulse RTS to reset device.");
			serial.pulseRTSLow();
			// Wait >2.6s -- 2s for the arduino reset; .6 seconds for the rest of the
			// system to come up.
			try {
				Thread.sleep(2600);
			} catch (InterruptedException ie) {
				// Assume we're shutting down the app or aborting the
				// attempt.  Reassert interrupted status and let
				// the thread wind down.
				Thread.currentThread().interrupt();
				return;
			}
			// One last attempt, post reset
			attemptConnection();
		}
	}

	/**
	 * Sends the command over the serial connection and retrieves a result.
	 */
	protected PacketResponse runCommand(byte[] packet) throws RetryException {
		return runCommand(packet,DEFAULT_RETRIES);
	}

	protected PacketResponse runQuery(byte[] packet, int retries) {
		try {
			return runCommand(packet,retries);
		} catch (RetryException re) {
			throw new RuntimeException("Queries can not have valid retries!");
		}
	}

	protected PacketResponse runQuery(byte[] packet) {
		return runQuery(packet,1);
	}
	
	void printDebugData(String title, byte[] data) {
		if (Base.logger.isLoggable(Level.FINER)) {
			StringBuffer buf = new StringBuffer(title + ": ");
			for (int i = 0; i < data.length; i++) {
				buf.append(Integer
						.toHexString((int) data[i] & 0xff));
				buf.append(" ");
			}
			Base.logger.log(Level.FINER,buf.toString());
		}
	}
	/**
	 * It's important here to understand the difference between "retries" and the retry exception.
	 * A retry is called when packet transmission itself failed and we want to try again.
	 * The retry exception is thrown when the packet was successfully processed, but the buffer
	 * was full, indicating to the controller that another attempt is warranted. 
	 * 
	 * If the specified number of retries is negative, the packet will be tried -N times, and
	 * no logging message will be displayed when the packet times out.  This is for "unreliable"
	 * packets (ordinarily, when scanning for toolheads).
	 * @param packet
	 * @param retries
	 * @return
	 * @throws RetryException
	 */
	protected PacketResponse runCommand(byte[] packet, int retries) throws RetryException {
		if (retries == 0) {
			Base.logger.severe("Packet timed out!");
			return PacketResponse.timeoutResponse();
		}
		if (packet == null || packet.length < 4) {
			Base.logger.severe("Attempt to send empty or too-small packet");
			return null; // skip empty commands or broken commands
		}

		boolean isCommand = (packet[2] & 0x80) != 0;
		if (fileCaptureOstream != null) {
			// capture to file.
			try {
				if (isCommand) { // ignore query commands
					fileCaptureOstream.write(packet,2,packet.length-3);
				} 
			} catch (IOException ioe) {
				// IOE should be very rare and shouldn't have to contaminate
				// our whole call stack; we'll wrap it in a runtime error.
				throw new RuntimeException(ioe);
			}
			return PacketResponse.okResponse();  // Always pretend that it's all good.
		}

		// This can actually happen during shutdown.
		if (serial == null) return PacketResponse.timeoutResponse();
		
		PacketProcessor pp;
		PacketResponse pr = new PacketResponse();

		assert (serial != null);

		synchronized(serial) {

			// Dump out if interrupted
			if (Thread.currentThread().isInterrupted()) {
				// Clear interrupted status
				Thread.interrupted();
				// Wait for end of packet and clear (if forthcoming)
				try {
					Thread.sleep(10);
					serial.clear();
				} catch (InterruptedException e) {
					// safe to ignore
				}
				// Reestablish interrupt
				Thread.currentThread().interrupt();
				return pr;
			}

			pp = new PacketProcessor();

			// Do not allow a stop or reset command to interrupt mid-packet!
			serial.write(packet);
			
			printDebugData("OUT",packet);

			// Read entire response packet
			boolean completed = false;
			while (!completed) {
				// Dump out if interrupted
				int b = serial.read();
				if (b == -1) {
					if (Thread.currentThread().isInterrupted()) {
						break;
					}
					if (retries > 1) {
						Base.logger.severe("Read timed out; retries remaining: "+Integer.toString(retries));
					}
					if (retries == -1) {
						// silently return a timeout response
						return PacketResponse.timeoutResponse();
					}
					else if (retries < 0) {
						return runCommand(packet, retries+1);
					}
					return runCommand(packet,retries-1);
				}
				try {
					completed = pp.processByte((byte) b);
				} catch (CRCException e) {
					Base.logger.severe("Bad CRC received; retries remaining: "+Integer.toString(retries));
					return runCommand(packet,retries-1);
				}
			}
			pr = pp.getResponse();

			if (pr.isOK()) {
				// okay!
			} else if (pr.getResponseCode() == PacketResponse.ResponseCode.BUFFER_OVERFLOW) {
				throw new RetryException();
			}
			else {
				// Other random error
				printDebugData("Unknown error sending, retry",packet);
				if (retries > 1) {
					return runCommand(packet,retries-1);
				}
			}
		}
		return pr;
	}

	static boolean isNotifiedFinishedFeature = false;

	public boolean isFinished() {
		if (fileCaptureOstream != null) { return true; }  // always done instantly if writing to file
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.IS_FINISHED.getCode());
		PacketResponse pr = runQuery(pb.getPacket());
		if (!pr.isOK()) { return false; }
		int v = pr.get8();
		if (pr.getResponseCode() == PacketResponse.ResponseCode.UNSUPPORTED) {
			if (!isNotifiedFinishedFeature) {
				Base.logger.severe("IsFinished not supported by this firmware. Update your firmware.");
				isNotifiedFinishedFeature = true;
			}
			return true;
		}
		boolean finished = (v != 0);
		Base.logger.log(Level.FINE,"Is finished: " + Boolean.toString(finished));
		return finished;
	}

	public void dispose() {
		super.dispose();
	}

	/***************************************************************************
	 * commands used internally to driver
	 **************************************************************************/
	public Version getVersionInternal() {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.VERSION.getCode());
		pb.add16(Base.VERSION);

		PacketResponse pr = runQuery(pb.getPacket(),1);
		if (pr.isEmpty()) return null;
		int versionNum = pr.get16();

		pb = new PacketBuilder(MotherboardCommandCode.GET_BUILD_NAME.getCode());
		pb.add16(Base.VERSION);

		String buildname = "";
		pr = runQuery(pb.getPacket(),1);
		if (!pr.isEmpty()) {
			byte[] payload = pr.getPayload();
			byte[] subarray = new byte[payload.length-1];
			System.arraycopy(payload, 1, subarray, 0, subarray.length);
			buildname = " (" + new String(subarray) + ")";
		}
		
		Base.logger.log(Level.FINE,"Reported version: "
					+ versionNum + " " + buildname);
		if (versionNum == 0) {
			Base.logger.severe("Null version reported!");
			return null;
		}
		Version v = new Version(versionNum / 100, versionNum % 100);
		Base.logger.warning("Motherboard firmware v" + v + buildname);

		final String MB_NAME = "RepRap Motherboard v1.X"; 
		FirmwareUploader.checkLatestVersion(MB_NAME, v);

		// Scan for each slave
		for (ToolModel t: getMachine().getTools()) {
			if (t != null) {
				initSlave(t.getIndex());
			}
		}
		// If we're dealing with older firmware, set timeout to infinity
		if (v.getMajor() < 2) {
			serial.setTimeout(Integer.MAX_VALUE);
		}
		return v;
	}
	
	private void initSlave(int toolIndex) {
		PacketBuilder slavepb = new PacketBuilder(MotherboardCommandCode.TOOL_QUERY.getCode());
		slavepb.add8((byte)toolIndex);
		slavepb.add8(ToolCommandCode.VERSION.getCode());
		int slaveVersionNum = 0;
		PacketResponse slavepr = runQuery(slavepb.getPacket(),-2);
		if (!slavepr.isEmpty()) {
			slaveVersionNum = slavepr.get16();
		}

		slavepb = new PacketBuilder(MotherboardCommandCode.TOOL_QUERY.getCode());
		slavepb.add8((byte)toolIndex);
		slavepb.add8(ToolCommandCode.GET_BUILD_NAME.getCode());
		slavepr = runQuery(slavepb.getPacket(),-2);

		String buildname = "";
		slavepr = runQuery(slavepb.getPacket(),1);
		if (!slavepr.isEmpty()) {
			byte[] payload = slavepr.getPayload();
			byte[] subarray = new byte[payload.length-1];
			System.arraycopy(payload, 1, subarray, 0, subarray.length);
			buildname = " (" + new String(subarray) + ")";
		}
		
		Base.logger.log(Level.FINE,"Reported slave board version: "
					+ slaveVersionNum + " " + buildname);
		if (slaveVersionNum == 0)
			Base.logger.severe("Toolhead "+Integer.toString(toolIndex)+": Not found.\nMake sure the toolhead is connected, the power supply is plugged in and turned on, and the power switch on the motherboard is on.");
        else
        {
            Version sv = new Version(slaveVersionNum / 100, slaveVersionNum % 100);
            toolVersion = sv;
            Base.logger.warning("Toolhead "+Integer.toString(toolIndex)+": Extruder controller firmware v" + sv + buildname);

            final String EC_NAME = "Extruder Controller v2.2"; 
    		FirmwareUploader.checkLatestVersion(EC_NAME, sv);
        }
	}

	public void sendInit() {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.INIT.getCode());
		runQuery(pb.getPacket());
	}

	/***************************************************************************
	 * commands for interfacing with the driver directly
	 * @throws RetryException 
	 **************************************************************************/

	public void queuePoint(Point3d p) throws RetryException {
		Base.logger.log(Level.FINE,"Queued point " + p);

		// is this point even step-worthy?
		Point3d deltaSteps = getAbsDeltaSteps(getCurrentPosition(), p);
		double masterSteps = getLongestLength(deltaSteps);

		// okay, we need at least one step.
		if (masterSteps > 0.0) {
			// where we going?
			Point3d steps = machine.mmToSteps(p);
			
			Point3d delta = getDelta(p);
			double feedrate = getSafeFeedrate(delta);
			
			// how fast are we doing it?
			long micros = convertFeedrateToMicros(getCurrentPosition(),
					p, feedrate);

			//System.err.println("Steps :"+steps.toString()+" micros "+Long.toString(micros));

			// okay, send it off!
			queueAbsolutePoint(steps, micros);
			

			super.queuePoint(p);
		}
	}

	//public Point3d getPosition() {
	//	return new Point3d();
	//}

	/*
	 * //figure out the axis with the most steps. Point3d steps =
	 * getAbsDeltaSteps(getCurrentPosition(), p); Point3d delta_steps =
	 * getDeltaSteps(getCurrentPosition(), p); int max = Math.max((int)steps.x,
	 * (int)steps.y); max = Math.max(max, (int)steps.z);
	 * 
	 * //get the ratio of steps to take each segment double xRatio = steps.x /
	 * max; double yRatio = steps.y / max; double zRatio = steps.z / max;
	 * 
	 * //how many segments will there be? int segmentCount = (int)Math.ceil(max /
	 * 32767.0);
	 * 
	 * //within our range? just do it. if (segmentCount == 1)
	 * queueIncrementalPoint(pb, delta_steps, ticks); else { for (int i=0; i<segmentCount;
	 * i++) { Point3d segmentSteps = new Point3d();
	 * 
	 * //TODO: is this accurate? //TODO: factor in negative deltas! //calculate
	 * our line segments segmentSteps.x = Math.round(32767 * xRatio);
	 * segmentSteps.y = Math.round(32767 * yRatio); segmentSteps.z =
	 * Math.round(32767 * zRatio);
	 * 
	 * //keep track of them. steps.x -= segmentSteps.x; steps.y -=
	 * segmentSteps.y; steps.z -= segmentSteps.z;
	 * 
	 * //send this segment queueIncrementalPoint(pb, segmentSteps, ticks); } }
	 */

	private void queueAbsolutePoint(Point3d steps, long micros) throws RetryException {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.QUEUE_POINT_ABS.getCode());

		if (Base.logger.isLoggable(Level.FINE)) {
			Base.logger.log(Level.FINE,"Queued absolute point " + steps + " at "
					+ Long.toString(micros) + " usec.");
		}

		// just add them in now.
		pb.add32((int) steps.x);
		pb.add32((int) steps.y);
		pb.add32((int) steps.z);
		pb.add32((int) micros);

		runCommand(pb.getPacket());
	}

	public void setCurrentPosition(Point3d p) throws RetryException {
//		System.err.println("   SCP: "+p.toString()+ " (current "+getCurrentPosition().toString()+")");
//		if (super.getCurrentPosition().equals(p)) return;
//		System.err.println("COMMIT: "+p.toString()+ " (current "+getCurrentPosition().toString()+")");
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.SET_POSITION.getCode());

		Point3d steps = machine.mmToSteps(p);
		pb.add32((long) steps.x);
		pb.add32((long) steps.y);
		pb.add32((long) steps.z);

		Base.logger.log(Level.FINE,"Set current position to " + p + " (" + steps
					+ ")");

		runCommand(pb.getPacket());

		super.setCurrentPosition(p);
	}

	public void homeAxes(EnumSet<Axis> axes, boolean positive, double feedrate) throws RetryException {
		Base.logger.log(Level.FINE,"Homing axes "+axes.toString());
		byte flags = 0x00;

		Point3d maxFeedrates = machine.getMaximumFeedrates();

		if (feedrate <= 0) {
			// figure out our fastest feedrate.
			feedrate = Math.max(maxFeedrates.x, maxFeedrates.y);
			feedrate = Math.max(maxFeedrates.z, feedrate);
		}
		
		Point3d target = new Point3d();
		
		if (axes.contains(Axis.X)) {
			flags += 1;
			feedrate = Math.min(feedrate, maxFeedrates.x);
			target.x = 1; // just to give us feedrate info.
		}
		if (axes.contains(Axis.Y)) {
			flags += 2;
			feedrate = Math.min(feedrate, maxFeedrates.y);
			target.y = 1; // just to give us feedrate info.
		}
		if (axes.contains(Axis.Z)) {
			flags += 4;
			feedrate = Math.min(feedrate, maxFeedrates.z);
			target.z = 1; // just to give us feedrate info.
		}
		
		// calculate ticks
		long micros = convertFeedrateToMicros(new Point3d(), target, feedrate);
		// send it!
		int code = positive?
				MotherboardCommandCode.FIND_AXES_MAXIMUM.getCode():
				MotherboardCommandCode.FIND_AXES_MINIMUM.getCode();
		PacketBuilder pb = new PacketBuilder(code);
		pb.add8(flags);
		pb.add32((int) micros);
		pb.add16(20); // default to 20 seconds
		runCommand(pb.getPacket());
		//Base.logger.info(String.valueOf(micros)); //spit out micros (For debugging purposes.)
}


	public void firstHoming(byte direction[], double XYfeedrate, double Zfeedrate) throws RetryException { //Auto homing first calibration script. Made by Intern Winter

		if (Base.logger.isLoggable(Level.FINER)) { //log the action
			Base.logger.log(Level.FINER,"Running first homing script.");
		}
		
		/*
		---order of packets to send---
		command_buffer.pop(); // remove the command
		pop8 direction packets (one for each axis)
		uint32_t feedrate = pop32(); // feedrate in us per step (one for XY and Z)
		uint16_t timeout_s = pop16(); //The time to home for before giving up.
		*/
		
		Point3d p = new Point3d(); //0,0,0. We already know that we should be here at the end of this.
		
		System.err.println("   SCP: "+p.toString()+ " (current "+getCurrentPosition().toString()+")");
		if (super.getCurrentPosition().equals(p)) {}
		else {super.setCurrentPosition(p); }

		super.setCurrentPosition(p);

		Point3d maxFeedrates = machine.getMaximumFeedrates();

		if (XYfeedrate <= 0) { //if feedrate for the XY is not entered, use the fastest
			// figure out our fastest feedrate.
			XYfeedrate = Math.max(maxFeedrates.x, maxFeedrates.y);
		} 
		if (Zfeedrate <= 0){ //if the feedrate for the Z stage is not entered, use the fastest
			Zfeedrate = maxFeedrates.z;
		}
		
		Point3d XYtarget = new Point3d();
		Point3d Ztarget = new Point3d();
		
		if (direction[0] != 0) {
			XYfeedrate = Math.min(XYfeedrate, maxFeedrates.x);
			XYtarget.x = 1; // just to give us feedrate info.
		}
		if (direction[1] != 0) {
			XYfeedrate = Math.min(XYfeedrate, maxFeedrates.y);
			XYtarget.y = 1; // just to give us feedrate info.
		}
		if (direction[2] != 0) {
			Zfeedrate = Math.min(Zfeedrate, maxFeedrates.z);
			Ztarget.z = 1; // just to give us feedrate info.
		}
		
		// calculate ticks
		long XYmicros = convertFeedrateToMicros(new Point3d(), XYtarget, XYfeedrate);
		long Zmicros = convertFeedrateToMicros(new Point3d(), Ztarget, Zfeedrate);
		// send it!
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.FIRST_AUTO_RAFT.getCode());
		
		for (int i = 0; i < 3; i++) {
		pb.add8(direction[i]); //send the directions!
		}
		
		pb.add32((int) XYmicros); //feedrate for XY (they move together and have the same feedrate)
		pb.add32((int) Zmicros);//Feedrate for Z stage
		pb.add16(150); // default homing timeout is 150 seconds. (usually only takes about 90 seconds).
		runCommand(pb.getPacket()); //send the command.		
		
	}
		


public void autoHoming(EnumSet<Axis> axes, double XYfeedrate, double Zfeedrate) throws RetryException { //Auto homing script. //Made by Intern Winter

	if (Base.logger.isLoggable(Level.FINER)) { //log the action
			Base.logger.log(Level.FINER,"Running homing script.");
		}
		
		/*
		---order of packets to send---
		command_buffer.pop(); // remove the command
		uint32_t feedrate = pop32(); // feedrate in us per step (one for XY and one for Z)
		uint16_t timeout_s = pop16(); //The time to home for before giving up.
		*/

		//We know where we should be. 0,0,0! (We only have to tell ourselves that we will be at 000. The machine will automatically know.)
		Point3d p = new Point3d(); //0,0,0
		
		System.err.println("   SCP: "+p.toString()+ " (current "+getCurrentPosition().toString()+")");
		if (super.getCurrentPosition().equals(p)) {}
		else {super.setCurrentPosition(p); }

		super.setCurrentPosition(p);

		Point3d maxFeedrates = machine.getMaximumFeedrates();

		if (XYfeedrate <= 0) { //if feedrate for the XY is not entered, use the fastest
			// figure out our fastest feedrate.
			XYfeedrate = Math.max(maxFeedrates.x, maxFeedrates.y);
		} 
		if (Zfeedrate <= 0){ //if the feedrate for the Z stage is not entered, use the fastest
			Zfeedrate = maxFeedrates.z;
		}
		
		Point3d XYtarget = new Point3d();
		Point3d Ztarget = new Point3d();
		
		if (axes.contains(Axis.X)) {
			XYfeedrate = Math.min(XYfeedrate, maxFeedrates.x);
			XYtarget.x = 1; // just to give us feedrate info.
		}
		if (axes.contains(Axis.Y)) {
			XYfeedrate = Math.min(XYfeedrate, maxFeedrates.y);
			XYtarget.y = 1; // just to give us feedrate info.
		}
		if (axes.contains(Axis.Z)) {
			Zfeedrate = Math.min(Zfeedrate, maxFeedrates.z);
			Ztarget.z = 1; // just to give us feedrate info.
		}
		
		// calculate ticks
		long XYmicros = convertFeedrateToMicros(new Point3d(), XYtarget, XYfeedrate);
		long Zmicros = convertFeedrateToMicros(new Point3d(), Ztarget, Zfeedrate);

		// send it!
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.AUTO_RAFT.getCode());
		pb.add32((int) XYmicros); //feedrate for XY stage
		pb.add32((int) Zmicros); //feedrate for the Z stage
		pb.add16(150); // default is 150 seconds. (usually only takes about 90)
		runCommand(pb.getPacket());
		
		
	}
	public void delay(long millis) throws RetryException {

		if (Base.logger.isLoggable(Level.FINER)) {
			Base.logger.log(Level.FINER,"Delaying " + millis + " millis.");
		}

		// send it!
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.DELAY.getCode());
		pb.add32(millis);
		runCommand(pb.getPacket());
	}

	public void openClamp(int clampIndex) {
		// TODO: throw some sort of unsupported exception.
		super.openClamp(clampIndex);
	}

	public void closeClamp(int clampIndex) {
		// TODO: throw some sort of unsupported exception.
		super.closeClamp(clampIndex);
	}

	public void enableDrives() throws RetryException {
		// Command RMB to enable its steppers. Note that they are
		// already automagically enabled by most commands and need
		// not be explicitly enabled.
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.ENABLE_AXES.getCode());
		pb.add8(0x9f); // enable all 5 axes
		runCommand(pb.getPacket());
		super.enableDrives();
	}

	public void disableDrives() throws RetryException {
		// Command RMB to disable its steppers.
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.ENABLE_AXES.getCode());
		pb.add8(0x1f); // disable all 5 axes
		runCommand(pb.getPacket());
		super.disableDrives();
	}

	public void changeGearRatio(int ratioIndex) {
		// TODO: throw some sort of unsupported exception.
		super.changeGearRatio(ratioIndex);
	}

	/**
	 * Will wait for first the tool, then the build platform, it exists and supported.
	 * Technically the platform is connected to a tool (extruder controller) 
	 * but this information is currently not used by the firmware.
	 * 
	 * timeout is given in seconds. If the tool isn't ready by then, the machine will continue anyway.
	 */
	public void requestToolChange(int toolIndex, int timeout) throws RetryException {
		selectTool(toolIndex);

		Base.logger.log(Level.FINE,"Waiting for tool #" + toolIndex);

		// send it!

		if (this.machine.currentTool().getTargetTemperature() > 0.0) {
			PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.WAIT_FOR_TOOL.getCode());
			pb.add8((byte) toolIndex);
			pb.add16(100); // delay between master -> slave pings (millis)
			pb.add16(timeout); // timeout before continuing (seconds)
			runCommand(pb.getPacket());
		}
		
		if (this.machine.getTool(toolIndex).hasHeatedPlatform() && 
			this.machine.currentTool().getPlatformTargetTemperature() > 0.0 &&
			getVersion().atLeast(new Version(2,4)) && toolVersion.atLeast(new Version(2,6))) {
			PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.WAIT_FOR_PLATFORM.getCode());
			pb.add8((byte) toolIndex);
			pb.add16(100); // delay between master -> slave pings (millis)
			pb.add16(timeout); // timeout before continuing (seconds)
			runCommand(pb.getPacket());
		}
	}

	public void selectTool(int toolIndex) throws RetryException {
		Base.logger.log(Level.FINE,"Selecting tool #" + toolIndex);

		// send it!
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.CHANGE_TOOL.getCode());
		pb.add8((byte) toolIndex);
		runCommand(pb.getPacket());

		super.selectTool(toolIndex);
	}

	/***************************************************************************
	 * Motor interface functions
	 **************************************************************************/
	public void setMotorRPM(double rpm) throws RetryException {
		// convert RPM into microseconds and then send.
		long microseconds = rpm == 0 ? 0 : Math.round(60.0 * 1000000.0 / rpm); // no
		// unsigned
		// ints?!?
		// microseconds = Math.min(microseconds, 2^32-1); // limit to uint32.

		Base.logger.log(Level.FINE,"Setting motor 1 speed to " + rpm + " RPM ("
					+ microseconds + " microseconds)");

		// send it!
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.SET_MOTOR_1_RPM.getCode());
		pb.add8((byte) 4); // length of payload.
		pb.add32(microseconds);
		runCommand(pb.getPacket());

		super.setMotorRPM(rpm);
	}

	public void setMotorSpeedPWM(int pwm) throws RetryException {
		Base.logger.log(Level.FINE,"Setting motor 1 speed to " + pwm + " PWM");

		// send it!
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.SET_MOTOR_1_PWM.getCode());
		pb.add8((byte) 1); // length of payload.
		pb.add8((byte) ((pwm > 255) ? 255 : pwm));
		runCommand(pb.getPacket());

		super.setMotorSpeedPWM(pwm);
	}

	public void enableMotor() throws RetryException {
		// our flag variable starts with motors enabled.
		byte flags = 1;

		// bit 1 determines direction...
		if (machine.currentTool().getMotorDirection() == ToolModel.MOTOR_CLOCKWISE)
			flags += 2;

		Base.logger.log(Level.FINE,"Toggling motor 1 w/ flags: "
					+ Integer.toBinaryString(flags));

		// send it!
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.TOGGLE_MOTOR_1.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8(flags);
		runCommand(pb.getPacket());

		super.enableMotor();
	}

	public void disableMotor() throws RetryException {
		// bit 1 determines direction...
		byte flags = 0;
		if (machine.currentTool().getSpindleDirection() == ToolModel.MOTOR_CLOCKWISE)
			flags += 2;

		Base.logger.log(Level.FINE,"Disabling motor 1");

		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.TOGGLE_MOTOR_1.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8(flags);
		runCommand(pb.getPacket());

		super.disableMotor();
	}

	public int getMotorSpeedPWM() {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_QUERY.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.GET_MOTOR_1_PWM.getCode());
		PacketResponse pr = runQuery(pb.getPacket());

		// get it
		int pwm = pr.get8();

		Base.logger.log(Level.FINE,"Current motor 1 PWM: " + pwm);

		// set it.
		machine.currentTool().setMotorSpeedReadingPWM(pwm);

		return pwm;
	}

	public double getMotorSpeedRPM() {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_QUERY.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.GET_MOTOR_1_RPM.getCode());
		PacketResponse pr = runQuery(pb.getPacket());

		// convert back to RPM
		long micros = pr.get32();
		double rpm = (60.0 * 1000000.0 / micros);

		Base.logger.log(Level.FINE,"Current motor 1 RPM: " + rpm + " (" + micros + ")");

		// set it.
		machine.currentTool().setMotorSpeedReadingRPM(rpm);

		return rpm;
	}

	/***************************************************************************
	 * PenPlotter interface functions
	 * @throws RetryException 
	 **************************************************************************/
	//public void moveServo(int degree) {}

	//public void enableServo() {}

	//public void disableServo() {}

	public void setServoPos(double degree) throws RetryException {
		
		Base.logger.log(Level.FINE,"Setting servo 1 position to " + degree + " degrees");

		// send it!
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.SET_SERVO_1_POS.getCode());
		pb.add8((byte) 1); // length of payload.
		pb.add8((byte) degree);
		runCommand(pb.getPacket());

		//super.setServoPos(degree);		
	}

public void setServo2Pos(double degree) throws RetryException {
		
		Base.logger.log(Level.FINE,"Setting servo 2 position to " + degree + " degrees");

		// send it!
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.SET_SERVO_2_POS.getCode());
		pb.add8((byte) 1); // length of payload.
		pb.add8((byte) degree);
		runCommand(pb.getPacket());

		//super.setServoPos(degree);		
	}

	
	
	
	/***************************************************************************
	 * Spindle interface functions
	 **************************************************************************/
	public void setSpindleRPM(double rpm) throws RetryException {
		// convert RPM into microseconds and then send.
		long microseconds = (int) Math.round(60 * 1000000 / rpm); // no
		// unsigned
		// ints?!?
		microseconds = Math.min(microseconds, 2 ^ 32 - 1); // limit to uint32.

		Base.logger.log(Level.FINE,"Setting motor 2 speed to " + rpm + " RPM ("
					+ microseconds + " microseconds)");

		// send it!
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.SET_MOTOR_2_RPM.getCode());
		pb.add8((byte) 4); // payload length
		pb.add32(microseconds);
		runCommand(pb.getPacket());

		super.setSpindleRPM(rpm);
	}

	public void setSpindleSpeedPWM(int pwm) throws RetryException {
		Base.logger.log(Level.FINE,"Setting motor 2 speed to " + pwm + " PWM");

		// send it!
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.SET_MOTOR_2_PWM.getCode());
		pb.add8((byte) 1); // length of payload.
		pb.add8((byte) pwm);
		runCommand(pb.getPacket());

		super.setMotorSpeedPWM(pwm);
	}

	public void enableSpindle() throws RetryException {
		// our flag variable starts with spindles enabled.
		byte flags = 1;

		// bit 1 determines direction...
		if (machine.currentTool().getSpindleDirection() == ToolModel.MOTOR_CLOCKWISE)
			flags += 2;

		Base.logger.log(Level.FINE,"Toggling motor 2 w/ flags: "
					+ Integer.toBinaryString(flags));

		// send it!
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.TOGGLE_MOTOR_2.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8(flags);
		runCommand(pb.getPacket());

		super.enableSpindle();
	}

	public void disableSpindle() throws RetryException {
		// bit 1 determines direction...
		byte flags = 0;
		if (machine.currentTool().getSpindleDirection() == ToolModel.MOTOR_CLOCKWISE)
			flags += 2;

		Base.logger.log(Level.FINE,"Disabling motor 2");

		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.TOGGLE_MOTOR_1.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8(flags);
		runCommand(pb.getPacket());

		super.disableSpindle();
	}

	public double getSpindleSpeedRPM() throws RetryException {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_QUERY.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.GET_MOTOR_2_RPM.getCode());
		PacketResponse pr = runCommand(pb.getPacket());

		// convert back to RPM
		long micros = pr.get32();
		double rpm = (60.0 * 1000000.0 / micros);

		Base.logger.log(Level.FINE,"Current motor 2 RPM: " + rpm + " (" + micros
					+ ")");

		// set it.
		machine.currentTool().setSpindleSpeedReadingRPM(rpm);

		return rpm;
	}

	public int getSpindleSpeedPWM() {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_QUERY.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.GET_MOTOR_2_PWM.getCode());
		PacketResponse pr = runQuery(pb.getPacket());

		// get it
		int pwm = pr.get8();

		Base.logger.log(Level.FINE,"Current motor 1 PWM: " + pwm);

		// set it.
		machine.currentTool().setSpindleSpeedReadingPWM(pwm);

		return pwm;
	}

	/***************************************************************************
	 * Temperature interface functions
	 * @throws RetryException 
	 **************************************************************************/
	public void setTemperature(double temperature) throws RetryException {
		// constrain our temperature.
		int temp = (int) Math.round(temperature);
		temp = Math.min(temp, 65535);

		Base.logger.log(Level.FINE,"Setting temperature to " + temp + "C");

		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.SET_TEMP.getCode());
		pb.add8((byte) 2); // payload length
		pb.add16(temp);
		runCommand(pb.getPacket());

		super.setTemperature(temperature);
	}

	public void readTemperature() {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_QUERY.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.GET_TEMP.getCode());
		PacketResponse pr = runQuery(pb.getPacket());
		if (pr.isEmpty()) return;
		int temp = pr.get16();
		machine.currentTool().setCurrentTemperature(temp);

		Base.logger.log(Level.FINE,"Current temperature: "
					+ machine.currentTool().getCurrentTemperature() + "C");

		super.readTemperature();
	}

	/***************************************************************************
	 * Platform Temperature interface functions
	 * @throws RetryException 
	 **************************************************************************/
	public void setPlatformTemperature(double temperature) throws RetryException {
		// constrain our temperature.
		int temp = (int) Math.round(temperature);
		temp = Math.min(temp, 65535);
		
		Base.logger.log(Level.FINE,"Setting platform temperature to " + temp + "C");
		
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.SET_PLATFORM_TEMP.getCode());
		pb.add8((byte) 2); // payload length
		pb.add16(temp);
		runCommand(pb.getPacket());
		
		super.setPlatformTemperature(temperature);
	}
	
	public void readPlatformTemperature() {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_QUERY.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.GET_PLATFORM_TEMP.getCode());
		PacketResponse pr = runQuery(pb.getPacket());
		if (pr.isEmpty()) return;
		int temp = pr.get16();
		machine.currentTool().setPlatformCurrentTemperature(temp);
		
		Base.logger.log(Level.FINE,"Current platform temperature: "
						+ machine.currentTool().getPlatformCurrentTemperature() + "C");
		
		super.readPlatformTemperature();
	}

	/***************************************************************************
	 * Flood Coolant interface functions
	 **************************************************************************/
	public void enableFloodCoolant() {
		// TODO: throw unsupported exception

		super.enableFloodCoolant();
	}

	public void disableFloodCoolant() {
		// TODO: throw unsupported exception

		super.disableFloodCoolant();
	}

	/***************************************************************************
	 * Mist Coolant interface functions
	 **************************************************************************/
	public void enableMistCoolant() {
		// TODO: throw unsupported exception

		super.enableMistCoolant();
	}

	public void disableMistCoolant() {
		// TODO: throw unsupported exception

		super.disableMistCoolant();
	}

	/***************************************************************************
	 * Fan interface functions
	 * @throws RetryException 
	 **************************************************************************/
	public void enableFan() throws RetryException {
		Base.logger.log(Level.FINE,"Enabling fan");

		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		int idx = machine.currentTool().getIndex();
		pb.add8((byte) idx);
		//pb.add8((byte) 0); // target 0 TODO FIXME !!!
		Base.logger.log(Level.FINE,"Tool index "+Integer.toString(idx));
		pb.add8(ToolCommandCode.TOGGLE_FAN.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8((byte) 1); // enable
		runCommand(pb.getPacket());

		super.enableFan();
	}

	public void disableFan() throws RetryException {
		Base.logger.log(Level.FINE,"Disabling fan");

		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.TOGGLE_FAN.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8((byte) 0); // disable
		runCommand(pb.getPacket());

		super.disableFan();
	}

	/***************************************************************************
	 * Valve interface functions
	 * @throws RetryException 
	 **************************************************************************/
	public void openValve() throws RetryException {
		Base.logger.log(Level.FINE,"Opening valve");

		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.TOGGLE_VALVE.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8((byte) 1); // enable
		runCommand(pb.getPacket());

		super.openValve();
	}

	public void closeValve() throws RetryException {
		Base.logger.log(Level.FINE,"Closing valve");

		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_COMMAND.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.TOGGLE_VALVE.getCode());
		pb.add8((byte) 1); // payload length
		pb.add8((byte) 0); // disable
		runCommand(pb.getPacket());

		super.closeValve();
	}

	/***************************************************************************
	 * Collet interface functions
	 **************************************************************************/
	public void openCollet() {
		// TODO: throw unsupported exception.

		super.openCollet();
	}

	public void closeCollet() {
		// TODO: throw unsupported exception.

		super.closeCollet();
	}

	/***************************************************************************
	 * Pause/unpause functionality for asynchronous devices
	 **************************************************************************/
	public void pause() {
		Base.logger.log(Level.FINE,"Sending asynch pause command");
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.PAUSE.getCode());
		runQuery(pb.getPacket());
	}

	public void unpause() {
		Base.logger.log(Level.FINE,"Sending asynch unpause command");
		// There is no explicit unpause command on the Sanguino3G; instead we
		// use the pause command to toggle the pause state.
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.PAUSE.getCode());
		runQuery(pb.getPacket());
	}

	/***************************************************************************
	 * Various timer and math functions.
	 **************************************************************************/

	private Point3d getDeltaDistance(Point3d current, Point3d target) {
		// calculate our deltas.
		Point3d delta = new Point3d();
		delta.x = target.x - current.x;
		delta.y = target.y - current.y;
		delta.z = target.z - current.z;

		return delta;
	}

	@SuppressWarnings("unused")
	private Point3d getDeltaSteps(Point3d current, Point3d target) {
		return machine.mmToSteps(getDeltaDistance(current, target));
	}

	private Point3d getAbsDeltaDistance(Point3d current, Point3d target) {
		// calculate our deltas.
		Point3d delta = new Point3d();
		delta.x = Math.abs(target.x - current.x);
		delta.y = Math.abs(target.y - current.y);
		delta.z = Math.abs(target.z - current.z);

		return delta;
	}

	private Point3d getAbsDeltaSteps(Point3d current, Point3d target) {
		return machine.mmToSteps(getAbsDeltaDistance(current, target));
	}

	/**
	 * 
	 * @param current
	 * @param target
	 * @param feedrate Feedrate in mm per minute
	 * @return
	 */
	private long convertFeedrateToMicros(Point3d current, Point3d target,
			double feedrate) {
		Point3d deltaDistance = getAbsDeltaDistance(current, target);
 		Point3d deltaSteps = machine.mmToSteps(deltaDistance);
		double masterSteps = getLongestLength(deltaSteps);
		// how long is our line length?
		double distance = deltaDistance.distance(new Point3d());
		// distance is in mm
		// feedrate is in mm/min
		// distance / feedrate * 60,000,000 = move duration in microseconds
		double micros = distance / feedrate * 60000000.0;
		// micros / masterSteps = time between steps for master axis.
		double step_delay = micros / masterSteps;
		return (long) Math.round(step_delay);
	}

	private double getLongestLength(Point3d p) {
		// find the dominant axis.
		if (p.x > p.y) {
			if (p.z > p.x)
				return p.z;
			else
				return p.x;
		} else {
			if (p.z > p.y)
				return p.z;
			else
				return p.y;
		}
	}

	public String getDriverName() {
		return "Sanguino3G";
	}

	/***************************************************************************
	 * Stop and system state reset
	 **************************************************************************/
	public void stop() {
		Base.logger.warning("Stop.");
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.ABORT.getCode());
		Thread.interrupted(); // Clear interrupted status
		PacketResponse pr = runQuery(pb.getPacket());
		// invalidate position, force reconciliation.
		invalidatePosition();
	}

	protected Point3d reconcilePosition() {
		if (fileCaptureOstream != null) {
			return new Point3d(0,0,0);
		}
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.GET_POSITION.getCode());
		PacketResponse pr = runQuery(pb.getPacket());
		Point3d steps = new Point3d(pr.get32(), pr.get32(), pr.get32());
		// Useful quickie debugs
//		System.err.println("Reconciling : "+machine.stepsToMM(steps).toString());
		return machine.stepsToMM(steps);
	}

	public void reset() {
		Base.logger.info("Reset.");
		if (isInitialized() && version.compareTo(new Version(1,4)) >= 0) {
			// WDT reset introduced in version 1.4 firmware
			PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.RESET.getCode());
			Thread.interrupted(); // Clear interrupted status
			PacketResponse pr = runQuery(pb.getPacket());
			// invalidate position, force reconciliation.
			invalidatePosition();
		}
		setInitialized(false);
		initialize();
	}

	private boolean eepromChecked = false;
	private static final int EEPROM_CHECK_LOW = 0x5A;
	private static final int EEPROM_CHECK_HIGH = 0x78;
	
	private void checkEEPROM() {
		if (!eepromChecked) {
			// Versions 2 and up have onboard eeprom defaults and rely on 0xff values
			eepromChecked = true;
			if (version.getMajor() < 2) {
				byte versionBytes[] = readFromEEPROM(EEPROM_CHECK_OFFSET,2);
				if (versionBytes == null || versionBytes.length < 2) return;
				if ((versionBytes[0] != EEPROM_CHECK_LOW) || 
						(versionBytes[1] != EEPROM_CHECK_HIGH)) {
					Base.logger.severe("Cleaning EEPROM to v1.X state");
					// Wipe EEPROM
					byte eepromWipe[] = new byte[16];
					Arrays.fill(eepromWipe,(byte)0x00);
					eepromWipe[0] = EEPROM_CHECK_LOW;
					eepromWipe[1] = EEPROM_CHECK_HIGH;
					writeToEEPROM(0,eepromWipe);
					Arrays.fill(eepromWipe,(byte)0x00);
					for (int i = 16; i < 256; i+=16) {
						writeToEEPROM(i,eepromWipe);
					}
				}
			}
		}
	}
	
	private void writeToEEPROM(int offset, byte[] data) {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.WRITE_EEPROM.getCode());
		pb.add16(offset);
		pb.add8(data.length);
		for (byte b : data) {
			pb.add8(b);
		}
		PacketResponse pr = runQuery(pb.getPacket());
		assert pr.get8() == data.length; 
	}
	
	private void writeToEEPROM32(int offset, long data) {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.WRITE_EEPROM32.getCode());
		pb.add16(offset);
		pb.add32(data);
		PacketResponse pr = runQuery(pb.getPacket());
		//assert pr.get32() == data.length; 
	}
	
	private void writeToEEPROM8(int offset, double data) {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.WRITE_EEPROM.getCode());
		pb.add16(offset);
		pb.add8(1);
		pb.add8((byte) data);
		PacketResponse pr = runQuery(pb.getPacket());
		//assert pr.get32() == data.length; 
	}

	
	private byte[] readFromToolEEPROM(int offset, int len) {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_QUERY.getCode());
		pb.add8((byte) machine.currentTool().getIndex());
		pb.add8(ToolCommandCode.READ_FROM_EEPROM.getCode());
		pb.add16(offset);
		pb.add8(len);
		PacketResponse pr = runQuery(pb.getPacket());
		if (pr.isOK()) {
			int rvlen = Math.min(pr.getPayload().length - 1,len);
			byte[] rv = new byte[rvlen];
			// Copy removes the first response byte from the packet payload.
			System.arraycopy(pr.getPayload(),1,rv,0,rvlen);
			return rv;
		}
		else
		{
			Base.logger.severe("On tool read: "+pr.getResponseCode().getMessage());
		}
		return null;
	}

	private void writeToToolEEPROM(int offset, byte[] data) {
		writeToToolEEPROM(offset, data, machine.currentTool().getIndex());
	}
	
	private void writeToToolEEPROM(int offset, byte[] data, int toolIndex) {
		final int MAX_PAYLOAD = 11;
		while (data.length > MAX_PAYLOAD) {
			byte[] head = new byte[MAX_PAYLOAD];
			byte[] tail = new byte[data.length-MAX_PAYLOAD];
			System.arraycopy(data,0,head,0,MAX_PAYLOAD);
			System.arraycopy(data,MAX_PAYLOAD,tail,0,data.length-MAX_PAYLOAD);
			writeToToolEEPROM(offset, head, toolIndex);
			offset += MAX_PAYLOAD;
			data = tail;
		}
		PacketBuilder slavepb = new PacketBuilder(MotherboardCommandCode.TOOL_QUERY.getCode());
		slavepb.add8((byte) toolIndex);
		slavepb.add8(ToolCommandCode.WRITE_TO_EEPROM.getCode());
		slavepb.add16(offset);
		slavepb.add8(data.length);
		for (byte b : data) {
			slavepb.add8(b);
		}
		PacketResponse slavepr = runQuery(slavepb.getPacket());
		slavepr.printDebug();
		assert slavepr.get8() == data.length; 
	}

	private byte[] readFromEEPROM(int offset, int len) {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.READ_EEPROM.getCode());
		pb.add16(offset);
		pb.add8(len);
		PacketResponse pr = runQuery(pb.getPacket());
		if (pr.isOK()) {
			int rvlen = Math.min(pr.getPayload().length - 1,len);
			byte[] rv = new byte[rvlen];
			// Copy removes the first response byte from the packet payload.
			System.arraycopy(pr.getPayload(),1,rv,0,rvlen);
			return rv;
		}
		return null;
	}
	
	/// EEPROM map:
	/// 00-01 - EEPROM data version
	/// 02    - Axis inversion byte
	/// 32-47 - Machine name (max. 16 chars)
	/// 0x100, 0x101, 0x102. 256, 257, 258. Autohome direction settings (0 for axis disabled, 1 for -, 2 for +) 1 per each axis.
	/// 0x103-06, 0x107-0x10a, 0x10b-0x10e. 259-262, 263-266, 267-270. Steps to move per axis. (Autohome axis varibles)
	/// 0x10f-0x112. 271-274. Amount to move Zstage up during a home or safemove.
	/// 0x113 275 Autohome extruder servo tool index
	/// 0x114 276 Autohome extruder Z_Probe lift angle
	/// 0x115 277 Autohome Extruder Z-Probe lowered angle
	
	final private static int EEPROM_CHECK_OFFSET = 0;
	final private static int EEPROM_MACHINE_NAME_OFFSET = 32;
	final private static int EEPROM_AUTOHOME_DIRECTIONS = 256;
	final private static int EEPROM_AUTOHOME_STEPS_PER_AXIS = 259;
	final private static int EEPROM_MM_TO_LIFT_ZSTAGE_AFTER_HOMING_OFFSET = 271;
	final private static int EEPROM_Z_PROBE_EXTRUDER_TOOL_INDEX = 275;
	final private static int EEPROM_Z_PROBE_EXTRUDER_LIFT_ANGLE = 276;
	final private static int EEPROM_Z_PROBE_EXTRUDER_LOWERED_ANGLE = 277;
	final private static int EEPROM_AXIS_INVERSION_OFFSET = 2;
	final private static int EEPROM_ENDSTOP_INVERSION_OFFSET = 3;
	final static class ECThermistorOffsets {
		final private static int[] TABLE_OFFSETS = {
			0x00f0,
			0x0170
		};

		final private static int R0 = 0x00;
		final private static int T0 = 0x04;
		final private static int BETA = 0x08;
		final private static int DATA = 0x10;
		
		public static int r0(int which) { return R0 + TABLE_OFFSETS[which]; }
		public static int t0(int which) { return T0 + TABLE_OFFSETS[which]; }
		public static int beta(int which) { return BETA + TABLE_OFFSETS[which]; }
		public static int data(int which) { return DATA + TABLE_OFFSETS[which]; }
	};	

	final private static int EC_EEPROM_EXTRA_FEATURES = 0x0018;
	final private static int EC_EEPROM_SLAVE_ID = 0x001A;

	final private static int MAX_MACHINE_NAME_LEN = 16;
	public EnumSet<Axis> getInvertedParameters() {
		checkEEPROM();
		byte[] b = readFromEEPROM(EEPROM_AXIS_INVERSION_OFFSET,1);
		EnumSet<Axis> r = EnumSet.noneOf(Axis.class);
		if ( (b[0] & (0x01 << 0)) != 0 ) r.add(Axis.X);
		if ( (b[0] & (0x01 << 1)) != 0 ) r.add(Axis.Y);
		if ( (b[0] & (0x01 << 2)) != 0 ) r.add(Axis.Z);
		return r;
	}

	public void setInvertedParameters(EnumSet<Axis> axes) {
		byte b[] = new byte[1];
		if (axes.contains(Axis.X)) b[0] = (byte)(b[0] | (0x01 << 0));
		if (axes.contains(Axis.Y)) b[0] = (byte)(b[0] | (0x01 << 1));
		if (axes.contains(Axis.Z)) b[0] = (byte)(b[0] | (0x01 << 2));
		writeToEEPROM(EEPROM_AXIS_INVERSION_OFFSET,b);
	}

	public String getMachineName() {
		checkEEPROM();
		byte[] data = readFromEEPROM(EEPROM_MACHINE_NAME_OFFSET,MAX_MACHINE_NAME_LEN);
		if (data == null) { return new String(); }
		try {
			int len = 0;
			while (len < MAX_MACHINE_NAME_LEN && data[len] != 0) len++;
			return new String(data,0,len,"ISO-8859-1");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			return null;
		}
	}

	public void setMachineName(String machineName) {
		machineName = new String(machineName);
		if (machineName.length() > 16) { 
			machineName = machineName.substring(0,16);
		}
		byte b[] = new byte[16];
		int idx = 0;
		for (byte sb : machineName.getBytes()) {
			b[idx++] = sb;
			if (idx == 16) break;
		}
		if (idx < 16) b[idx] = 0;
		writeToEEPROM(EEPROM_MACHINE_NAME_OFFSET,b);
	}
	
	public void setZstageMMtoLift(String MMtoLift) {
		MMtoLift = new String(MMtoLift);
		int aInt = Integer.valueOf(MMtoLift);
		if (aInt < 0) { //No negatives please. This is a value for lift, not dig.
		aInt = -aInt;
		}
		Point3d mmtolift = new Point3d();
		mmtolift.z = aInt;
		Point3d steps = machine.mmToSteps(mmtolift);
		//pb.add32((long) steps.x);
		//pb.add32((long) steps.y);
		//pb.add32((long) steps.z);
		writeToEEPROM32(EEPROM_MM_TO_LIFT_ZSTAGE_AFTER_HOMING_OFFSET,(long) steps.z);
	}
	
	public void setZProbeSettings(String servoLiftPos, String servoLoweredPos, byte toolIndex) {
		servoLiftPos = new String(servoLiftPos);
		double liftPos;
		liftPos = Double.valueOf(servoLiftPos);
		writeToEEPROM8(EEPROM_Z_PROBE_EXTRUDER_LIFT_ANGLE, liftPos);
		
		servoLiftPos = new String(servoLoweredPos);
		double loweredPos;
		loweredPos = Double.valueOf(servoLoweredPos);
		writeToEEPROM8(EEPROM_Z_PROBE_EXTRUDER_LOWERED_ANGLE, loweredPos);
	
		
		//toolIndex = new String(toolIndex);
		byte toolIndexByte[] = new byte[1];
		toolIndexByte[0] = toolIndex;
		writeToEEPROM(EEPROM_Z_PROBE_EXTRUDER_TOOL_INDEX, toolIndexByte);
	
	}
	
	public boolean hasFeatureOnboardParameters() {
		if (!isInitialized()) return false;
		return version.compareTo(new Version(1,2)) >= 0; 
	}

	public void createThermistorTable(int which, double r0, double t0, double beta) {
		// Generate a thermistor table for r0 = 100K.
		final int ADC_RANGE = 1024;
		final int NUMTEMPS = 20;
		byte table[] = new byte[NUMTEMPS*2*2];
		class ThermistorConverter {
			final double ZERO_C_IN_KELVIN = 273.15;
			public double vadc,rs,vs,k,beta;
			public ThermistorConverter(double r0, double t0C, double beta, double r2) {
				this.beta = beta;
				this.vs = this.vadc = 5.0;
				final double t0K = ZERO_C_IN_KELVIN + t0C;
				this.k = r0 * Math.exp(-beta / t0K);
				this.rs = r2;		
			}
			public double temp(double adc) {
				// Convert ADC reading into a temperature in Celsius
				double v = adc * this.vadc / ADC_RANGE;
				double r = this.rs * v / (this.vs - v);
				return (this.beta / Math.log(r / this.k)) - ZERO_C_IN_KELVIN;
			}
		};
		ThermistorConverter tc = new ThermistorConverter(r0,t0,beta,4700.0);
		double adc = 1; // matching the python script's choices for now;
		// we could do better with this distribution.
		for (int i = 0; i < NUMTEMPS; i++) {
			double temp = tc.temp(adc);
			// extruder controller is little-endian
			int tempi = (int)temp;
			int adci = (int)adc;
			Base.logger.fine("{ "+Integer.toString(adci) +"," +Integer.toString(tempi)+" }");
			table[(2*2*i)+0] = (byte)(adci & 0xff); // ADC low
			table[(2*2*i)+1] = (byte)(adci >> 8); // ADC high
			table[(2*2*i)+2] = (byte)(tempi & 0xff); // temp low
			table[(2*2*i)+3] = (byte)(tempi >> 8); // temp high
			adc += (ADC_RANGE/(NUMTEMPS-1));
		}
		// Add indicators
		byte eepromIndicator[] = new byte[2];
		eepromIndicator[0] = EEPROM_CHECK_LOW;
		eepromIndicator[1] = EEPROM_CHECK_HIGH;
		writeToToolEEPROM(0,eepromIndicator);

		writeToToolEEPROM(ECThermistorOffsets.beta(which),intToLE((int)beta));
		writeToToolEEPROM(ECThermistorOffsets.r0(which),intToLE((int)r0));
		writeToToolEEPROM(ECThermistorOffsets.t0(which),intToLE((int)t0));
		writeToToolEEPROM(ECThermistorOffsets.data(which),table);
	}

	private byte[] intToLE(int s, int sz) {
		byte buf[] = new byte[sz];
		for (int i = 0; i < sz; i++) {
			buf[i] = (byte)(s & 0xff);
			s = s >>> 8;
		}
		return buf;
	}

	private byte[] floatToLE(float f) {
		byte buf[] = new byte[2];
		double d = f;
		double intPart = Math.floor(d);
		double fracPart = Math.floor((d-intPart)*256.0);		
		buf[0] = (byte)intPart;
		buf[1] = (byte)fracPart;
		return buf;
	}

	private byte[] intToLE(int s) {
		return intToLE(s,4);
	}

	ResponseCode convertSDCode(int code) {
		switch (code) {
		case 0:
			return ResponseCode.SUCCESS;
		case 1:
			return ResponseCode.FAIL_NO_CARD;
		case 2:
			return ResponseCode.FAIL_INIT;
		case 3:
			return ResponseCode.FAIL_PARTITION;
		case 4:
			return ResponseCode.FAIL_FS;
		case 5:
			return ResponseCode.FAIL_ROOT_DIR;
		case 6:
			return ResponseCode.FAIL_LOCKED;
		case 7:
			return ResponseCode.FAIL_NO_FILE;
		default:
		}
		return ResponseCode.FAIL_GENERIC;
	}

	FileOutputStream fileCaptureOstream = null;
	
	public void beginFileCapture(String path) throws FileNotFoundException {
		fileCaptureOstream = new FileOutputStream(new File(path));
	}
	
	public void endFileCapture() throws IOException {
		fileCaptureOstream.close();
		fileCaptureOstream = null;
	}
	
	public ResponseCode beginCapture(String filename) {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.CAPTURE_TO_FILE.getCode());
		for (byte b : filename.getBytes()) {
			pb.add8(b);
		}
		pb.add8(0); // null-terminate string
		PacketResponse pr = runQuery(pb.getPacket());
		return convertSDCode(pr.get8());
	}

	public int endCapture() {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.END_CAPTURE.getCode());
		PacketResponse pr = runQuery(pb.getPacket());
		return pr.get32();
	}

	public ResponseCode playback(String filename) {
		PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.PLAYBACK_CAPTURE.getCode());
		for (byte b : filename.getBytes()) {
			pb.add8(b);
		}
		pb.add8(0); // null-terminate string
		PacketResponse pr = runQuery(pb.getPacket());
		return convertSDCode(pr.get8());
	}

	public boolean hasFeatureSDCardCapture() {
		if (!isInitialized()) return false;
		return version.compareTo(new Version(1,3)) >= 0; 
	}
	
	public List<String> getFileList() {
		Vector<String> fileList = new Vector<String>();
		boolean reset = true;
		while (true) {
			PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.NEXT_FILENAME.getCode());
			pb.add8(reset?1:0);
			reset = false;
			PacketResponse pr = runQuery(pb.getPacket());
			ResponseCode rc = convertSDCode(pr.get8());
			if (rc != ResponseCode.SUCCESS) {
				return fileList;
			}
			StringBuffer s = new StringBuffer();
			while (true) {
				char c = (char)pr.get8();
				if (c == 0) break;
				s.append(c);
			}
			if (s.length() == 0) break;
			fileList.add(s.toString());
		}
		return fileList;
	}

	public int getBeta(int which) {
		byte r[] = readFromToolEEPROM(ECThermistorOffsets.beta(which),4);
		int val = 0;
		for (int i = 0; i < 4; i++) {
			val = val + (((int)r[i] & 0xff) << 8*i);
		}
		return val;
	}

	public int getR0(int which) {
		byte r[] = readFromToolEEPROM(ECThermistorOffsets.r0(which),4);
		int val = 0;
		for (int i = 0; i < 4; i++) {
			val = val + (((int)r[i] & 0xff) << 8*i);
		}
		return val;
	}

	public int getT0(int which) {
		byte r[] = readFromToolEEPROM(ECThermistorOffsets.t0(which),4);
		int val = 0;
		for (int i = 0; i < 4; i++) {
			val = val + (((int)r[i] & 0xff) << 8*i);
		}
		return val;
	}

	final static class ECBackoffOffsets {
		/// Backoff stop time, in ms: 2 bytes
		final static int STOP_MS = 0x0004;
		/// Backoff reverse time, in ms: 2 bytes
		final static int REVERSE_MS = 0x0006;
		/// Backoff forward time, in ms: 2 bytes
		final static int FORWARD_MS = 0x0008;
		/// Backoff trigger time, in ms: 2 bytes
		final static int TRIGGER_MS = 0x000A;
	};
	
	final static class PIDOffsets {
		final static int PID_EXTRUDER  = 0x000C;
		final static int PID_HBP       = 0x0012;
		final static int P_TERM_OFFSET = 0x0000;
		final static int I_TERM_OFFSET = 0x0002;
		final static int D_TERM_OFFSET = 0x0004;
	};	
	
	private int read16FromToolEEPROM(int offset, int defaultValue) {
		byte r[] = readFromToolEEPROM(offset,2);
		int val = ((int)r[0])&0xff;
		val += (((int)r[1])&0xff) << 8;
		if (val == 0x0ffff) return defaultValue;
		return val;
	}

	private int byteToInt(byte b) { return ((int)b)&0xff; }
	
	private float readFloat16FromToolEEPROM(int offset, float defaultValue) {
		byte r[] = readFromToolEEPROM(offset,2);
		if (r[0] == (byte)0xff && r[1] == (byte)0xff) return defaultValue;
		return (float)byteToInt(r[0]) + ((float)byteToInt(r[1]))/256.0f;
	}

	public BackoffParameters getBackoffParameters() {
		BackoffParameters bp = new BackoffParameters();
		bp.forwardMs = read16FromToolEEPROM(ECBackoffOffsets.FORWARD_MS, 300);
		bp.stopMs = read16FromToolEEPROM(ECBackoffOffsets.STOP_MS, 5);
		bp.reverseMs = read16FromToolEEPROM(ECBackoffOffsets.REVERSE_MS, 500);
		bp.triggerMs = read16FromToolEEPROM(ECBackoffOffsets.TRIGGER_MS, 300);
		return bp;
	}
	
	public void setBackoffParameters(BackoffParameters bp) {
		writeToToolEEPROM(ECBackoffOffsets.FORWARD_MS,intToLE(bp.forwardMs,2));
		writeToToolEEPROM(ECBackoffOffsets.STOP_MS,intToLE(bp.stopMs,2));
		writeToToolEEPROM(ECBackoffOffsets.REVERSE_MS,intToLE(bp.reverseMs,2));
		writeToToolEEPROM(ECBackoffOffsets.TRIGGER_MS,intToLE(bp.triggerMs,2));
	}

	public PIDParameters getPIDParameters(int which) {
		PIDParameters pp = new PIDParameters();
		int offset = (which == 0)?PIDOffsets.PID_EXTRUDER:PIDOffsets.PID_HBP;
		pp.p = readFloat16FromToolEEPROM(offset+PIDOffsets.P_TERM_OFFSET, 7.0f);
		pp.i = readFloat16FromToolEEPROM(offset+PIDOffsets.I_TERM_OFFSET, 0.325f);
		pp.d = readFloat16FromToolEEPROM(offset+PIDOffsets.D_TERM_OFFSET, 36.0f);
		return pp;
	}
	
	public void setPIDParameters(int which, PIDParameters pp) {
		int offset = (which == 0)?PIDOffsets.PID_EXTRUDER:PIDOffsets.PID_HBP;
		writeToToolEEPROM(offset+PIDOffsets.P_TERM_OFFSET,floatToLE(pp.p));
		writeToToolEEPROM(offset+PIDOffsets.I_TERM_OFFSET,floatToLE(pp.i));
		writeToToolEEPROM(offset+PIDOffsets.D_TERM_OFFSET,floatToLE(pp.d));
	}

	/** Reset to the factory state.  This ordinarily means writing 0xff over the
	 * entire eeprom.
	 */
	public void resetToFactory() {
		byte eepromWipe[] = new byte[16];
		Arrays.fill(eepromWipe,(byte)0xff);
		for (int i = 0; i < 0x0200; i+=16) {
			writeToEEPROM(i,eepromWipe);
		}
	}

	public void resetToolToFactory() {
		byte eepromWipe[] = new byte[16];
		Arrays.fill(eepromWipe,(byte)0xff);
		for (int i = 0; i < 0x0200; i+=16) {
			writeToToolEEPROM(i,eepromWipe);
		}
	}

	public EndstopType getInvertedEndstops() {
		checkEEPROM();
		byte[] b = readFromEEPROM(EEPROM_ENDSTOP_INVERSION_OFFSET,1);
		return EndstopType.endstopTypeForValue(b[0]);
	}

	public void setInvertedEndstops(EndstopType endstops) {
		byte b[] = new byte[1];
		b[0] = endstops.getValue();
		writeToEEPROM(EEPROM_ENDSTOP_INVERSION_OFFSET,b);
	}

	public ExtraFeatures getExtraFeatures() {
		int efdat = read16FromToolEEPROM(EC_EEPROM_EXTRA_FEATURES,0x4084);
		ExtraFeatures ef = new ExtraFeatures();
		ef.swapMotorController = (efdat & 0x0001) != 0;
		ef.heaterChannel = (efdat >> 2) & 0x0003;
		ef.hbpChannel = (efdat >> 4) & 0x0003;
		ef.abpChannel = (efdat >> 6) & 0x0003;
//		System.err.println("Extra features: smc "+Boolean.toString(ef.swapMotorController));
//		System.err.println("Extra features: ch ext "+Integer.toString(ef.heaterChannel));
//		System.err.println("Extra features: ch hbp "+Integer.toString(ef.hbpChannel));
//		System.err.println("Extra features: ch abp "+Integer.toString(ef.abpChannel));
		return ef;
	}
	
	public void setExtraFeatures(ExtraFeatures features) {
		int efdat = 0x4000;
		if (features.swapMotorController) { efdat = efdat | 0x0001; }
		efdat |= features.heaterChannel << 2;
		efdat |= features.hbpChannel << 4;
		efdat |= features.abpChannel << 6;
		//System.err.println("Writing to EF: "+Integer.toHexString(efdat));
		writeToToolEEPROM(EC_EEPROM_EXTRA_FEATURES,intToLE(efdat,2));
	}

	
	public double getPlatformTemperatureSetting() {
		// This call was introduced in version 2.3
		if (toolVersion.atLeast(new Version(2,3))) {
			PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_QUERY.getCode());
			pb.add8((byte) machine.currentTool().getIndex());
			pb.add8(ToolCommandCode.GET_PLATFORM_SP.getCode());
			PacketResponse pr = runQuery(pb.getPacket());
			int sp = pr.get16();
			machine.currentTool().setPlatformTargetTemperature(sp);
		}		
		return super.getPlatformTemperatureSetting();
	}

	public double getTemperatureSetting() {
		// This call was introduced in version 2.3
		if (toolVersion.atLeast(new Version(2,3))) {
			PacketBuilder pb = new PacketBuilder(MotherboardCommandCode.TOOL_QUERY.getCode());
			pb.add8((byte) machine.currentTool().getIndex());
			pb.add8(ToolCommandCode.GET_SP.getCode());
			PacketResponse pr = runQuery(pb.getPacket());
			int sp = pr.get16();
			machine.currentTool().setTargetTemperature(sp);
		}
		return super.getTemperatureSetting();
	}

	public Version getToolVersion() { return toolVersion; }

	public boolean setConnectedToolIndex(int index) {
		byte[] data = new byte[1];
		data[0] = (byte) index;
		writeToToolEEPROM(EC_EEPROM_SLAVE_ID, data, 255);
		return false;
	}

	public boolean toolsCanBeReindexed() {
		return true;
	}

	public boolean supportsSimultaneousTools() {
		return true;
	}
}
