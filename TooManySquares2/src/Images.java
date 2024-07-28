import java.io.File;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.ArrayList;
import java.awt.Graphics2D;

/**
 * Images must be 512x512 px jpg/png/gif/. They must have integer names too.
 * 
 * Fragment values: Average color, brightness contrast, saturation contrast.
 * Board values: stability
 */
public class Images extends Thread {

	private static Images images = null;

	private File parentFolder;

	private BufferedImage[][][] imageFragments;

	private int[][] currentBoard; // which fragments are currently being displayed, holds their index in
									// imageFragments[x][y].

	private int[][][] imgWeights; // holds values to favor image fragments that haven't been displayed recently,
									// and to favor source image nucleation.
									// SUM OF imgweights[c][c][n] over n IS NORMED TO
									// imageFragments[c][c].length, always renorm after each fragment selection.

	private static float directions[][]; // vectors pointing 8 directions counterclockwise from bottom middle (towards
											// positive y), normed to 1.
	private static int taxiDirections[][]; // taxicab version of directions[][];

	private float[][][] propvector; // where nucleation points propagate: [board coordinate][board
									// coordinate][(x-component, y-component)]. Normed to 1.
	private static int dN = 5;
	private double directionBonus = 20.0; // dot product multiplier
	private int propWeak = 1; // amount of favor to randomness over propagation, minimum is 8.
	private float propSpread = (float) 0.01; // inversely proportional to amount propagation vector direction is
												// preserved
	private static int[] crystalBoost; // Gives propagation boosts to select img fragments, boost might not be even.
	private static int crystalBoostAgeMax = 15; // crystalBoost resets every time crystalBoostAgeMax*random whole
												// board's worth of
												// fragments is replaced
	private static int crystalCountFrac = 3; // how many maintainFragment calls count for one getFragment call for
												// updateCounter
	private static int crystalBoostMult = 10; // multiplier of crystal boost over default proximity boost
	private static int updateCounter; // counts calls to getFragment;
	private static int crystalUpdateCounter; // counts calls to maintainFragment;
	private static double randPosStatic; // stores Math.random();

	private boolean[][] crystals; // mark non-edge pieces of source image polyfragments and how long they've been
									// in there.
	private int[][] crystalAge;// mark ages of crystals in here (how many times maintainFragment has been
								// called), ages are inherited.
	private static int ageLimit = 120; // crystal fragments at this age limit don't propagate and don't maintain their
										// fragment.

	private static int iterationsPerRender = 4000; // how many times the board updates per render. 2-4000 is good for
													// 30 fps

	private static BufferedImage boardImage;

	public static boolean imgBlocker = false;
	public static boolean imgInterrupter = false;

	public static BufferedImage getBoardImage() {
		return boardImage;
	}

	public static void initializeImages(String imageFolder, int n) {
		System.out.println("Folder set to " + imageFolder);
		images = new Images(imageFolder);
		parseImages(n);

		// startup task: create random board, find crystals, build image
		for (int x = 0; x < images.imageFragments.length; x++) {
			for (int y = 0; y < images.imageFragments.length; y++) {
				getFragmentStart(x, y);
			}
		}
		findCrystals();
		buildBoardImage();

		// start image thread
		images.start();
	}

	public void run() {
		while (true) {
			for (int i = 0; i < iterationsPerRender; i++) {
				if (imgInterrupter) {
					imgInterrupter = false;
					break;
				} else {
					// update one random fragment
					int x = (int) (Math.random() * imageFragments.length);
					int y = (int) (Math.random() * imageFragments.length);
					if (Images.isCrystal(x, y)) {
						Images.maintainFragment(x, y);
					} else {
						Images.getFragment(x, y);
					}
				}
			}
			buildBoardImage();
		}
	}

	private static void buildBoardImage() {
		BufferedImage temp = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
		Graphics2D tempGraph = temp.createGraphics();
		int fragSize = 512 / images.imageFragments.length;
		for (int x = 0; x < images.imageFragments.length; x++) {
			for (int y = 0; y < images.imageFragments.length; y++) {
				tempGraph.drawImage(images.imageFragments[x][y][images.currentBoard[x][y]], null, x * fragSize,
						y * fragSize);
			}
		}
		tempGraph.dispose();

		// ew thread synchronization...
		synchronized (PicSquare.monitor) {
			while (imgBlocker) {
				try {
					PicSquare.monitor.wait();
				} catch (InterruptedException e) {
					// shouldn't happen
				}
			}
			imgInterrupter = false;
			boardImage = temp;
			imgBlocker = true;
			PicSquare.picBlocker = false;
			PicSquare.monitor.notify();
		}
	}

	private Images(String imageFolder) {
		parentFolder = new File(imageFolder);
		directions = new float[][] { new float[] { 0, 1 },
				new float[] { (float) (1 / Math.sqrt(2)), (float) (1 / Math.sqrt(2)) }, new float[] { 1, 0 },
				new float[] { (float) (1 / Math.sqrt(2)), -(float) (1 / Math.sqrt(2)) }, new float[] { 0, -1 },
				new float[] { -(float) (1 / Math.sqrt(2)), -(float) (1 / Math.sqrt(2)) }, new float[] { -1, 0 },
				new float[] { -(float) (1 / Math.sqrt(2)), (float) (1 / Math.sqrt(2)) } };
		taxiDirections = new int[][] { new int[] { 0, 1 }, new int[] { 1, 1 }, new int[] { 1, 0 }, new int[] { 1, -1 },
				new int[] { 0, -1 }, new int[] { -1, -1 }, new int[] { -1, 0 }, new int[] { -1, 1 } };
		updateCounter = 0;
		randPosStatic = Math.random();
	}

	/*
	 * loads and orders image fragments, fills imgWeights with 1's.
	 */
	private static void parseImages(int n) {
		int n2 = (int) Math.pow(2, n);
		System.out.println("loading images...");

		// initialize object arrays
		File[] imageFiles = images.parentFolder.listFiles();
		BufferedImage[] img = new BufferedImage[imageFiles.length];
		images.imageFragments = new BufferedImage[n2][n2][imageFiles.length];
		images.currentBoard = new int[n2][n2];
		images.imgWeights = new int[n2][n2][imageFiles.length];
		images.propvector = new float[n2][n2][2];
		images.crystals = new boolean[n2][n2];
		images.crystalAge = new int[n2][n2];

		int[] places = new int[imageFiles.length];
		int[] names = new int[imageFiles.length];

		// load file names.
		for (int i = 0; i < imageFiles.length; i++) {
			places[i] = i;
			names[i] = Integer.parseInt(imageFiles[i].getName().substring(0, imageFiles[i].getName().length() - 4));
		}

		// sort filenames into predictable order
		boolean nsorted = true;
		int temp;
		while (nsorted) {
			nsorted = false;
			for (int i = 0; i < names.length - 1; i++) {
				if (names[i] > names[i + 1]) {
					nsorted = true;
					temp = names[i];
					names[i] = names[i + 1];
					names[i + 1] = temp;
					temp = places[i];
					places[i] = places[i + 1];
					places[i + 1] = temp;

				}
			}
		}

		// sort images into the order given by filename and generate image fragments
		File[] imageFilesSorted = new File[imageFiles.length];
		for (int i = 0; i < places.length; i++) {
			imageFilesSorted[i] = imageFiles[places[i]];
		}
		int fragLength = (int) (512 / n2);
		for (int i = 0; i < imageFilesSorted.length; i++) {
			try {
				img[i] = ImageIO.read(imageFilesSorted[i]);
				for (int x = 0; x < n2; x++) {
					for (int y = 0; y < n2; y++) {
						images.imageFragments[x][y][i] = img[i].getSubimage(x * fragLength, y * fragLength, fragLength,
								fragLength);
					}
				}
			} catch (Exception e) {
				System.out.println("parseImages: " + e);
			}
		}

		// fill imgWeights with all 1's, crystalAge with all 0's.
		for (int i = 0; i < n2; i++) {
			for (int j = 0; j < n2; j++) {
				for (int k = 0; k < imageFiles.length; k++) {
					images.imgWeights[i][j][k] = 1;
				}
				images.crystalAge[i][j] = 0;
			}
		}

		System.out.println("images loaded.");
	}

	/**
	 * for repainting after startup
	 */
	private static void getFragment(int x, int y) {
		// update counter and crystalBoost
		updateCounter();

		// create new nucleation propagation vector
		images.propvector[x][y] = getNewPropvector(x, y);

		// select image
		int img = selectImageFragent(x, y);

		// update nucleation propogation vector
		updatePropVector(x, y, img);

		// update currentboard
		images.currentBoard[x][y] = img;

		// reevaluate crystal status of new fragment and neighboring blocks
		updateCrystal(x, y);
		for (int j = 0; j < 8; j++) {
			try {
				updateCrystal(x + taxiDirections[j][0], y + taxiDirections[j][1]);
			} catch (ArrayIndexOutOfBoundsException e) {
				// do nothing, just checked a box that is offscreen.
			}
		}
	}

	/**
	 * for repainting if x,y is a crystal fragment
	 */
	private static void maintainFragment(int x, int y) {
		if (images.crystalAge[x][y] < ageLimit) {
			updateCrystalCounter();
			images.crystalAge[x][y]++;
		} else {
			getFragment(x, y);
		}
	}

	/**
	 * for painting board on startup when board isn't fully filled yet
	 */
	private static void getFragmentStart(int x, int y) {
		int fragment = (int) (Math.random() * images.imageFragments[0][0].length);
		images.currentBoard[x][y] = fragment;
		images.propvector[x][y] = getNewPropvector(x, y);
	}

	/**
	 * creates a new nucleation propagation vector that increasingly favors pointing
	 * away from closest edges the closer you are to those edges. Otherwise points
	 * in a random direction.
	 * 
	 * @param x board coordinate
	 * @param y board coordinate
	 */
	private static float[] getNewPropvector(int x, int y) {
		float xweight = (images.currentBoard.length - 2 * x) / images.currentBoard.length;
		float yweight = (images.currentBoard.length - 2 * y) / images.currentBoard.length;
		float rand = (float) (2 * (0.5 - Math.random()));
		float prand = (float) Math.random();
		float vx = (rand + prand * xweight) / (1 + prand * xweight);
		rand = (float) (2 * (0.5 - Math.random()));
		prand = (float) Math.random();
		float vy = (rand + prand * yweight) / (1 + prand * yweight);
		float norm = (float) Math.sqrt(vx * vx + vy * vy);
		vx = vx / norm;
		vy = vy / norm;
		return new float[] { vx, vy };
	}

	/**
	 * 
	 * 
	 * @param x board coordinate
	 * @param y board coordinate
	 */
	private static int selectImageFragent(int x, int y) {
		// updates imgWeights for a board coordinate based on propagation of surrounding
		// current board fragments
		double addprop = ((double) images.imageFragments[0][0].length) / images.propWeak;
		int counter = 0;
		ArrayList<Integer> surrounding = new ArrayList<Integer>();
		ArrayList<Integer> surroundingOldValues = new ArrayList<Integer>();
		boolean NotInSurrounding = true;
		for (int i = 0; i < 8; i++) {
			NotInSurrounding = true;
			try {

				// record surrounding fragment sources and their weights at the current fragment
				for (int j = 0; j < surrounding.size(); j++) {
					if (images.currentBoard[x + taxiDirections[i][0]][y + taxiDirections[i][1]] == surrounding.get(j)) {
						NotInSurrounding = false;
					}
				}
				if (NotInSurrounding) {
					surrounding.add(images.currentBoard[x + taxiDirections[i][0]][y + taxiDirections[i][1]]);
					surroundingOldValues.add(images.imgWeights[x][y][images.currentBoard[x + taxiDirections[i][0]][y
							+ taxiDirections[i][1]]]);
				}

				// check to see if the surrounding fragment isn't a senile crystal
				if (images.crystalAge[x + taxiDirections[i][0]][y + taxiDirections[i][1]] < ageLimit) {
					// add propagation weight using magnitude of dot product of direction and prop
					// vectors
					images.imgWeights[x][y][images.currentBoard[x + taxiDirections[i][0]][y
							+ taxiDirections[i][1]]] += (int) (images.directionBonus * addprop
									* Math.max(-1.0 / (6.0 * images.propWeak), directions[i][0]
											* images.propvector[x + taxiDirections[i][0]][y + taxiDirections[i][1]][0]
											+ directions[i][1] * images.propvector[x + taxiDirections[i][0]][y
													+ taxiDirections[i][1]][1]));

					// add default proximity bonus
					images.imgWeights[x][y][images.currentBoard[x + taxiDirections[i][0]][y
							+ taxiDirections[i][1]]] += addprop / images.propWeak;

					// add crystal boost bonus
					for (int k = 0; k < crystalBoost.length; k++) {
						if (images.currentBoard[x + taxiDirections[i][0]][y
								+ taxiDirections[i][1]] == crystalBoost[k]) {
							images.imgWeights[x][y][images.currentBoard[x + taxiDirections[i][0]][y
									+ taxiDirections[i][1]]] += crystalBoostMult * addprop / images.propWeak;
						}
					}

					// if total sum of effects is negative, just set to 0.
					if (images.imgWeights[x][y][images.currentBoard[x + taxiDirections[i][0]][y
							+ taxiDirections[i][1]]] < 0) {
						images.imgWeights[x][y][images.currentBoard[x + taxiDirections[i][0]][y
								+ taxiDirections[i][1]]] = 0;
					}
				}

			} catch (ArrayIndexOutOfBoundsException e) {
				// just checked an offscreen square, ignore.
			}
		}

		// calculate new imgWeights size & store in counter
		counter = 0;
		while (counter == 0) {
			for (int i = 0; i < images.imageFragments[0][0].length; i++) {
				counter += images.imgWeights[x][y][i];
			}
			if (counter == 0) {
				images.imgWeights[x][y][(int) (Math.random() * images.imageFragments[0][0].length)] = 1;
			}
		}

		// choose an image
		int imgRand = (int) (Math.random() * counter);
		int imgWeightSum = 0;
		int img = -1;
		try {
			while (imgWeightSum <= imgRand) {
				imgWeightSum += images.imgWeights[x][y][img + 1];
				img++;
			}
		} catch (Exception e) {
			System.out.println("AIOBE: c=" + counter + "   imgWS=" + imgWeightSum + "   imgR=" + imgRand);
		}
		if (img == -1) {
			int zeroCounter = 0;
			try {
				while (images.imgWeights[x][y][zeroCounter] == 0) {
					zeroCounter++;
				}
			} catch (ArrayIndexOutOfBoundsException e) {
				// do nothing
			}
			img = (int) (Math.random() * (zeroCounter + 1));
			if (img > images.imgWeights[x][y].length) {
				img--;
			}
		}

		// replace propagation weights with original weights, and change the weight of
		// displayed fragment to 0
		counter -= images.imgWeights[x][y][img];
		images.imgWeights[x][y][img] = 0;
		for (int i = 0; i < surrounding.size(); i++) {
			if (surrounding.get(i) != img) {
				counter -= images.imgWeights[x][y][i] - surroundingOldValues.get(i);
				images.imgWeights[x][y][i] = surroundingOldValues.get(i);
			}
		}

		// renorm imgWeights
		double intCut = 0;
		double full = 0;
		if (counter != 0) {
			double mult = ((double) images.imageFragments[0][0].length) / ((double) counter);
			for (int i = 0; i < images.imageFragments[0][0].length; i++) {
				full = images.imgWeights[x][y][i] * mult;
				intCut += full - ((int) full);
				images.imgWeights[x][y][i] = (int) full;
			}
			for (int i = 0; i < Math.round(intCut); i++) {
				imgRand = (int) (Math.random() * images.imageFragments[0][0].length);
				if (imgRand != img) {
					images.imgWeights[x][y][imgRand]++;
				} else {
					i--;
				}
			}
		} else {
			for (int i = 0; i < images.imageFragments[0][0].length; i++) {
				imgRand = (int) (Math.random() * images.imageFragments[0][0].length);
				if (imgRand != img) {
					images.imgWeights[x][y][imgRand]++;
				} else {
					i--;
				}
			}

		}

		return img;
	}

	/**
	 * Updates prop vector of a new piece if it is part of a growing crystal
	 * 
	 * @param x   board coordinate
	 * @param y   board coordinate
	 * @param img image fragment id
	 */
	private static void updatePropVector(int x, int y, int img) {
		float xComp = 0;
		float yComp = 0;
		for (int i = 0; i < 8; i++) {
			for (int c = 0; c < 2; c++) {
				try {
					if (img == images.currentBoard[x + taxiDirections[i][0]][y + taxiDirections[i][1]]) {
						xComp += images.propvector[x + taxiDirections[i][0]][y + taxiDirections[i][1]][0];
						yComp += images.propvector[x + taxiDirections[i][0]][y + taxiDirections[i][1]][1];
					}
				} catch (ArrayIndexOutOfBoundsException e) {
					// just checked an offscreen square, ignore.
				}
			}
		}

		// factor in new independent propvector
		xComp = images.propvector[x][y][0] + xComp / images.propSpread;
		yComp = images.propvector[x][y][1] + yComp / images.propSpread;

		// normalize to 1 and assign new propvector
		if (xComp != 0 && yComp != 0) {
			images.propvector[x][y][0] = (float) (xComp / Math.sqrt((double) (xComp * xComp + yComp * yComp)));
			images.propvector[x][y][1] = (float) (yComp / Math.sqrt((double) (xComp * xComp + yComp * yComp)));
		} else {
			images.propvector[x][y] = getNewPropvector(x, y);
		}
	}

	private static void updateCrystal(int x, int y) {
		boolean noiseExterminator = true;
		boolean crystal = true;
		int frag = images.currentBoard[x][y];
		for (int i = 0; i < 8; i++) {
			try {
				if (crystal || noiseExterminator) {
					if (noiseExterminator) {
						noiseExterminator = (frag == images.currentBoard[x + taxiDirections[i][0]][y
								+ taxiDirections[i][1]]);
					} else if (crystal) {
						crystal = (frag == images.currentBoard[x + taxiDirections[i][0]][y + taxiDirections[i][1]]);
					}

				}
			} catch (ArrayIndexOutOfBoundsException e) {
				// just checked an offscreen box
				if (noiseExterminator) {
					noiseExterminator = false;
				}

			}

		}
		images.crystals[x][y] = crystal;

		// inherit age from oldest neighbor
		if (crystal) {
			int maxAge = 0;
			try {
				for (int i = 0; i < 8; i++) {
					if (images.crystalAge[x + taxiDirections[i][0]][y + taxiDirections[i][1]] > maxAge) {
						maxAge = images.crystalAge[x + taxiDirections[i][0]][y + taxiDirections[i][1]];
					}
				}
			} catch (ArrayIndexOutOfBoundsException e) {
				// ignore, just checked an offscreen block
			}
			images.crystalAge[x][y] = maxAge;
		} else {
			images.crystalAge[x][y] = 0;
		}
	}

	public static boolean isCrystal(int x, int y) {
		return images.crystals[x][y];
	}

	private static void findCrystals() {
		for (int x = 0; x < images.imageFragments.length; x++) {
			for (int y = 0; y < images.imageFragments.length; y++) {
				updateCrystal(x, y);
			}
		}

	}

	private static void updateCounter() {
		if (updateCounter % ((int) ((crystalBoostAgeMax * randPosStatic + 1) * images.imageFragments.length
				* images.imageFragments.length)) == 0) {
			if (Math.random() > 0.5) {
				crystalBoost = new int[(int) (Math.random() * (dN + 1))];
				for (int i = 0; i < crystalBoost.length; i++) {
					crystalBoost[i] = (int) (Math.random() * images.imageFragments[0][0].length);
					System.out.println(crystalBoost[i]);
				}
			} else {
				crystalBoost = new int[0];
			}
			System.out.println("- - - - - - - - ");
			updateCounter = 0;
			randPosStatic = Math.random();

		}
		updateCounter++;
	}

	private static void updateCrystalCounter() {
		if (crystalUpdateCounter % crystalCountFrac == crystalCountFrac - 1) {
			updateCounter++;
			crystalUpdateCounter = 0;
		}
		crystalUpdateCounter++;
	}
}
