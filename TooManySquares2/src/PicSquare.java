import javax.swing.JPanel;
import java.awt.Graphics;

public class PicSquare extends JPanel {
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	public static Object monitor = new Object();
	public static boolean picBlocker = false;

	public PicSquare() {
		super();
		this.setBounds(0, 0, 512, 512);
	}

	public void paintComponent(Graphics g) {

		synchronized (monitor) {
			while (picBlocker) {
				try {
					Images.imgInterrupter = true;
					monitor.wait();
				} catch (InterruptedException e) {
					// this shouldn't happen.
				}
			}
			picBlocker = true;
			Images.imgBlocker = false;
			monitor.notify();
		}
		super.paintComponent(g);
		g.drawImage(Images.getBoardImage(), 0, 0, null);
	}
}
