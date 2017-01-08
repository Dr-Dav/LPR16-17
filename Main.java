package com.texttwist.client;
import javax.swing.SwingUtilities;

public class Main implements Runnable {

	public static void main(String[] args) {
		System.setProperty("apple.eawt.quitStrategy", "CLOSE_ALL_WINDOWS"); // Trap cmd+q in Mac Systems
		SwingUtilities.invokeLater(new Main());
	}
	
	@Override
	public void run() {
		try {
			ClientConfiguration config = new ClientConfiguration("clientConfig.conf");
			@SuppressWarnings("unused")
			TwistClient c = new TwistClient(config);
		}
		catch(Exception e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}

}
