package localization;
import util.Point;
import util.Util;
import data.DataCenter;
import drivers.HWConstants;
import drivers.Navigation;
import interfaces.CSListener;

/**
 * Class to perform localization using the color sensor.
 * 
 * @author Andrei Purcarus
 *
 */
public class CSLocalizer implements CSListener {
	/**
	 * Number of ms to wait after finishing localization to allow
	 * other processes to update.
	 */
	private static final long TIMEOUT = 100;
	
	/**
	 * The angle in degrees by which to correct the final angle of the localization.
	 */
	private static final double CORRECTION_FACTOR = 0;
	
	/**
	 * The number of grid lines to detect during localization.
	 */
	private static final int NUM_LINES = 4;
	/**
	 * The maximum number of times to attempt localization before giving up.
	 */
	private static final int MAX_TRIES = 3;
	/**
	 * The minimal delay in ms between consecutive pings.
	 */
	private static final long DELAY = 200;
	/**
	 * The NUM_LINES {x, y, theta} tuples for grid line detection.
	 */
	private double[][] data;
	/**
	 * The number of grid lines detected.
	 */
	private int count;
	/**
	 * The System time in ms at which the last ping occured.
	 */
	private long lastPing;

	/**
	 * The location of the data where localization is applied.
	 */
	private DataCenter dc;

	/**
	 * The navigation system to use to move the robot.
	 */
	private Navigation nav;

	/**
	 * Default constructor.
	 * @param dc The data location to send localization to.
	 * @param nav The navigation to use to move the robot.
	 */
	public CSLocalizer(DataCenter dc, Navigation nav) {
		this.dc = dc;
		this.nav = nav;
		data = new double[NUM_LINES][3];
		count = 0;
		lastPing = System.currentTimeMillis();
	}

	/**
	 * Performs color sensor localization. Robot must be facing
	 * roughly 45 degrees counterclockwise from the +x axis. A CSPoller
	 * must be running using the same DataCenter as this object.
	 */
	public void doLocalization() {
		doLocalization(new Point(0, 0));
	}

	/**
	 * Performs color sensor localization around (xGrid, yGrid). Robot must be facing
	 * roughly 45 degrees counterclockwise from the +x axis. A CSPoller
	 * must be running using the same DataCenter as this object. Takes grid
	 * as being the grid line intersection that it is supposed to detect.
	 * The distances are in cm.
	 * @param grid The position of the grid line to localize against.
	 */
	public void doLocalization(Point grid) {
		//Rotates 360 degrees until it clocks NUM_LINES grid lines in one complete
		//rotation. The localization assumes it starts roughly facing 45 
		//degrees counterclockwise from the +x axis. Therefore it should 
		//detect the grid lines in the order {x, y, x, y} when turning clockwise.
		
		int numberOfTries = 0;
		dc.addListener(this);
		while (count != NUM_LINES) {
			//Turns 360 degrees clockwise.
			nav.turn(-360);
			if (count != NUM_LINES) {
				data = new double[NUM_LINES][3];
				count = 0;
				++numberOfTries;
			}
			if (numberOfTries > MAX_TRIES) {
				//Gives up if it tries to localize too many times.
				dc.removeListener(this);
				return;
			}
		}
		dc.removeListener(this);
		count = 0;
		
		//Computes the difference between the x and y angles measured.
		//Makes the difference be in the (-180, 180] range.
		double[] diffs = getXAndYDiff(data[0][2], data[1][2], data[2][2], data[3][2]);
		double thetaXDiff = diffs[0];
		double thetaYDiff = diffs[1];
		thetaXDiff = Util.toRange(thetaXDiff, -180.0, true);
		thetaYDiff = Util.toRange(thetaYDiff, -180.0, true);
		
		//Computes the NUM_LINES offsets from actual values for the NUM_LINES 
		//orientation angles measured previously and averages them.
		double[] actual = new double[NUM_LINES];
		if (thetaXDiff >= 0) {
			actual[0] = 270.0 + thetaXDiff / 2;
			actual[2] = 270.0 - thetaXDiff / 2;
		} else {
			actual[0] = 90.0 + thetaXDiff / 2;
			actual[2] = 90.0 - thetaXDiff / 2;
		}
		if (thetaYDiff >= 0) {
			actual[1] = 180.0 + thetaYDiff / 2;
			actual[3] = 180.0 - thetaYDiff / 2;
		} else {
			actual[1] = thetaYDiff / 2;
			actual[3] = -thetaYDiff / 2;
		}
		for (int i = 0; i < NUM_LINES; ++i) {
			actual[i] = Util.toRange(actual[i], 0.0, false);
		}
		double[] changes = new double[NUM_LINES];
		for (int i = 0; i < NUM_LINES; ++i) {
			changes[i] = actual[i] - data[i][2];
			changes[i] = Util.toRange(changes[i], -180.0, true);
		}
		double sum = 0;
		for (double error : changes)
			sum += error;
		double averageError = sum / NUM_LINES;
				
		double x, y;
		if (thetaYDiff >= 0.0)
			x = grid.x - HWConstants.CS_DISTANCE * Math.cos(Math.toRadians(thetaYDiff)/2);
		else
			x = grid.x + HWConstants.CS_DISTANCE * Math.cos(Math.toRadians(thetaYDiff)/2);
		if (thetaXDiff >= 0.0)
			y = grid.y - HWConstants.CS_DISTANCE * Math.cos(Math.toRadians(thetaXDiff)/2);
		else
			y = grid.y + HWConstants.CS_DISTANCE * Math.cos(Math.toRadians(thetaXDiff)/2);
		
		//Gets current distance.
		double[] dist = dc.getXYT();
		//Corrects the position.
		double actualAngle = dist[2] + averageError + CORRECTION_FACTOR;
		actualAngle = Util.toRange(actualAngle, 0.0, false);
		dc.setXYT(x, y, actualAngle);
		
		//TODO
		nav.travelTo(grid, false);
		nav.turnTo(85);
		while (dc.getCSValue() > 47) {
			nav.turn(0.5);
		}
		double tmin = dc.getTheta();
		while (dc.getCSValue() <= 46) {
			nav.turn(0.5);
		}
		double tmax = dc.getTheta();
		double tavg = (tmin + tmax) / 2;
		dc.setTheta(tmax + (90 - tavg));
		//TODO
		
		try {
			Thread.sleep(TIMEOUT);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * The method to be called to notify the
	 * listener of a grid line detection by
	 * the color sensor.
	 */
	@Override
	public void ping() {
		long currentPing = System.currentTimeMillis();
		if (currentPing - lastPing > DELAY) {
			if (count < NUM_LINES) {
				data[count] = dc.getXYT();
				++count;
			} else {
				count = NUM_LINES + 1;
			}
			lastPing = currentPing;
		}
	}
	
	/**
	 * Tests all possible combinations for the given angles and finds the best one
	 * to localize with. The angles x1, y1, x2, y2 are the headings of the robot
	 * as it turns 360 degrees and detects lines.
	 * @return the difference in x angles (x1-x2) in position 0 and the difference in
	 * 		   y angles (y1-y2) in position 1 of the array.
	 */
	private double[] getXAndYDiff(double x1, double y1, double x2, double y2) {
		for (int j = 0; j < 4; ++j) {
			double thetaXDiff = Math.signum(1.5 - j) * (x1 - x2);
			double thetaYDiff = Math.signum(0.5 - (j % 2)) * (y1 - y2);
			
			double[] actual = new double[NUM_LINES];
			if (thetaXDiff >= 0) {
				actual[0] = 270.0 + thetaXDiff / 2;
				actual[2] = 270.0 - thetaXDiff / 2;
			} else {
				actual[0] = 90.0 + thetaXDiff / 2;
				actual[2] = 90.0 - thetaXDiff / 2;
			}
			if (thetaYDiff >= 0) {
				actual[1] = 180.0 + thetaYDiff / 2;
				actual[3] = 180.0 - thetaYDiff / 2;
			} else {
				actual[1] = thetaYDiff / 2;
				actual[3] = -thetaYDiff / 2;
			}
			for (int i = 0; i < NUM_LINES; ++i) {
				actual[i] = Util.toRange(actual[i], 0.0, false);
			}
			double[] changes = new double[NUM_LINES];
			for (int i = 0; i < NUM_LINES; ++i) {
				changes[i] = actual[i] - data[i][2];
				changes[i] = Util.toRange(changes[i], -180.0, true);
			}
			double sum = 0;
			for (double error : changes)
				sum += error;
			double averageError = sum / NUM_LINES;
			if (averageError >= 315 || averageError <= 45) {
				double[] result = new double[2];
				result[0] = thetaXDiff;
				result[1] = thetaYDiff;
				return result;
			}
		}
		double[] result = {(x1-x2), (y1-y2)};
		return result;
	}
}
