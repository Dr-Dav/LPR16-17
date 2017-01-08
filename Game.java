package com.texttwist.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class Game implements Runnable {
	/* Ricevuti dall'esterno */
	private SocketChannel lettersChannel; // It's already opened and blocking, has to be used only for receiving the letters
	private GUI gui;
	private String myUsername;
	private String creator;
	
	/* Interni */
	private DatagramChannel multicastChannel; // Receives the results
	private DatagramChannel wordsChannel; // Sends the words typed by the user
	private ReentrantLock lock;
	private Condition waiting;
	private ArrayList<String> words; // Won't contain duplicates nor empty words
	
	private ExecutorService executor; // Use a thread to listen the multicastChannel and get realtime results
	
	public Game(ClientConfiguration config, SocketChannel lettersChannel, String multicastGroup, GUI gui, String myUsername, String creator) {
		this.lettersChannel = lettersChannel;
		this.gui = gui;
		this.myUsername = myUsername;
		this.creator = creator;
		this.executor = Executors.newSingleThreadExecutor();
		
		try {
			this.wordsChannel = DatagramChannel.open();
			if(config.getServerAddress().equalsIgnoreCase("localhost") || config.getServerAddress().equals("127.0.0.1")) {
				this.wordsChannel.connect(new InetSocketAddress(InetAddress.getLocalHost(), config.getUDPPort()));
			}
			else {
				this.wordsChannel.connect(new InetSocketAddress(InetAddress.getByName(config.getServerAddress()), config.getUDPPort()));
			}
			this.multicastChannel = DatagramChannel.open(StandardProtocolFamily.INET);
			this.multicastChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
			this.multicastChannel.bind(new InetSocketAddress(config.getUDPPort()));
			this.multicastChannel.join(InetAddress.getByName(multicastGroup),
									   NetworkInterface.getByInetAddress(InetAddress.getLocalHost()));
		}
		catch(IOException e) {
			this.multicastChannel = null;
			e.printStackTrace();
		}
		
		this.executor.submit(new MulticastListener(this.multicastChannel, this.gui)); // Parte subito così nel caso li prende tutti
		
		this.words = null;
		
		this.lock = new ReentrantLock();
		this.waiting = this.lock.newCondition();
	}

	public void addWords(ArrayList<String> words) {
		this.lock.lock();
		try {
			this.words = words;
			this.waiting.signal();
		}
		finally {
			this.lock.unlock();
		}
	}
	
	@Override
	public void run() {
		ByteBuffer buf = ByteBuffer.allocate(1024);
		System.out.println("partito il run del game");
		
		// Per prima cosa, ricevo le lettere
		try {
			System.out.println("Il mio socket è: "+this.lettersChannel);
			System.out.flush();
			this.lettersChannel.read(buf);
			this.lettersChannel.close();
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}
		String letters = new String(buf.array(), 0, buf.position());
		if(letters.startsWith("[ERROR-REFUSED]")) {
			String[] usersWhoRefused = letters.split(" ");
			// Associa l'array con gli utenti che hanno rifiutato, togliendo il messaggio di errore [ERROR-REFUSED]
			// Remove any previous java object binding because we are using the same error page for multiple error types
			this.gui.removeJavaBinding("timeout");
			this.gui.removeJavaBinding("usersOffline");
			this.gui.bindJavaObject("refused", Arrays.copyOfRange(usersWhoRefused, 1, usersWhoRefused.length));
			this.gui.openPage("error.html");
			return;
		}
		else if(letters.equals("[ERROR-TIMEOUT]")) { // Gli utenti non hanno accettato/rifiutato entro questo periodo
			this.gui.removeJavaBinding("refused");
			this.gui.removeJavaBinding("usersOffline");
			this.gui.bindJavaObject("timeout", true);
			this.gui.openPage("error.html");
			return;
		}
		System.out.println("ricevute le lettere");
		this.gui.bindJavaObject("letters", letters);
		this.gui.openPage("match.html");
		this.gui.resize(600, 400);
		// Lettere ricevute e inviate alla GUI, ora aspetto che l'utente scriva le parole
		
		this.lock.lock();
		try {
			while(this.words == null)
				this.waiting.await();
		} catch (InterruptedException e) {
			// Interrotto qui vuol dire che era scaduto il timeout e l'utente non ha premuto START
			
			this.executor.shutdown();
			try {
				this.executor.awaitTermination(5, TimeUnit.MINUTES);
			}
			catch(InterruptedException e1) {
				e1.printStackTrace();
			}
			
			System.out.println("Gioco INTERROTTO!");
			
			return;
		}
		finally {
			this.lock.unlock();
		}
		
		// Dentro words ho le parole senza duplicati e senza parole vuote
		// Wordschannel è già connesso
		System.out.println("Le parole inserite sono: "+this.words);
		// Ora mando le parole
		String standardMessage = "[WORD] " + this.creator + " " + this.myUsername;
		for(String word : this.words) {
			boolean sent = false;
			while(!sent) {
				buf.clear();
				buf.put(standardMessage.concat(" "+word).getBytes());
				buf.flip();
				try {
					this.wordsChannel.write(buf);
				} catch (IOException e) {
					e.printStackTrace();
				}
				// Devo ricevere l'ack
				buf.clear();
				try {
					this.wordsChannel.read(buf);
				}
				catch(IOException e) {
					e.printStackTrace();
				}
				if(new String(buf.array(), 0, buf.position()).equals("[ACK]")) { // Ok!
					sent = true;
				}
				// Altrimenti riprova a mandarla?
			}
		}
		buf.clear();
		buf.put(new String("[END] " + this.creator + " " + this.myUsername).getBytes());
		buf.flip();
		try {
			this.wordsChannel.write(buf);
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		/* FINE TRASMISSIONE PAROLE */
		
		
		//System.out.println("I risultati sono: "+results);
		this.gui.executeJS("var nav = document.getElementById('backToMain');"
				+ "nav.href='main.html';"
				+ "nav.onclick = function() { GameManager.resetSize(); };"); // Per consentire di tornare alla home
		
		
		this.executor.shutdown();
		try {
			this.executor.awaitTermination(5, TimeUnit.MINUTES);
		}
		catch(InterruptedException e) {
			e.printStackTrace();
		}
		
		System.out.println("Gioco finito!");
	}

}
