import java.awt.EventQueue;
import javax.swing.JFrame;
import javax.swing.Timer;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;

public class Gui implements ActionListener {

	private JFrame uwuframe;
	private PicSquare image;
	private Timer timer;

	/**
	 * Launch the application.
	 */
	public static void main(String[] args) {
		// massive start: n=8, ratem=32768

		int n = 7; // controls partition, 2^(2n) = partitions of image display. n should be from 0
					// to 9. WARNING: 9 might blow up your computer's memory.
		Images.initializeImages(System.getProperty("user.dir")+"/fursonas", n);
		EventQueue.invokeLater(new Runnable() {
			public void run() {
				try {
					Gui window = new Gui(n);
					window.uwuframe.setVisible(true);
					System.out.println("GUI visible.");
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}

	/**
	 * Create the application.
	 */
	public Gui(int n) {
		initialize();
		timer = new Timer(30, this);
		timer.setInitialDelay(1000);
		timer.start();
		System.out.println("Timer started.");
	}

	/**
	 * Initialize the contents of the frame.
	 */
	private void initialize() {
		System.out.println("initializing GUI...");
		uwuframe = new JFrame();
		uwuframe.setTitle("uwu uwu uwu uwu");
		uwuframe.setBounds(100, 100, 513, 513);
		uwuframe.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		uwuframe.getContentPane().setLayout(null);
		image = new PicSquare();
		uwuframe.getContentPane().add(image);
		System.out.println("GUI initialized.");
	}

	public void actionPerformed(ActionEvent e) {
		image.repaint();
	}
}
