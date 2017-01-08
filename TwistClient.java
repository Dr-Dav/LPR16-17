package com.texttwist.client;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.texttwist.server.RMITwist;


public class TwistClient implements GameManager, AccountManager {
	private GUI gui;
	private Callback rmiCallback; // My callback
	private RemoteUser exportedRmiCallback;
	private RMITwist rmiServer; // Server
	private ExecutorService executor;
	private Game currentGame;
	private Future<?> currentGameThread;
	private InviteManager inviteManager;
	private ClientConfiguration config;
	
	public TwistClient(ClientConfiguration config) throws RemoteException, NotBoundException {
		this.config = config;
	
		this.gui = new GUI("TextTwist!", 300, 500, this);
		
		this.executor = Executors.newFixedThreadPool(2); // Per schedulare il thread di gioco e l'invitemanager

		// Non c'è bisogno di fare rebind perché si passa a mano al server
		this.rmiCallback = new Callback(this);
		this.rmiServer = (RMITwist) LocateRegistry.getRegistry(config.getServerAddress(), config.getRMIPort()).lookup(RMITwist.NAME); // Prendo lo stub del server
		this.exportedRmiCallback = (RemoteUser) UnicastRemoteObject.exportObject(this.rmiCallback, 0);
		
		this.currentGame = null; // No game in progress
		this.currentGameThread = null;
		this.inviteManager = new InviteManager(config);
		
		this.gui.openPage("splash.html");
		this.gui.bindJavaObjects(this);
	}
	
	public void close() {
		try {
			this.executor.shutdown();
			this.executor.awaitTermination(1, TimeUnit.HOURS);
			// Il logout lo fa già la gui
			UnicastRemoteObject.unexportObject(this.rmiCallback, true);
		}
		catch(InterruptedException e) {
			System.exit(-1);
		}
		catch(NoSuchObjectException e) { e.printStackTrace(); }
		System.exit(0); // Explicitly close because we are using GUI
	}
	
	@Override
	public void register(String username, String password) {
		try {
			this.gui.executeJS("$('#fail).hide();");
			if(this.rmiServer.register(username, password)) {
				//this.gui.executeJS("$('#regForm').hide(); $('#ok').text('You are now registered!').fadeIn('slow');");
				login(username, password); // Automatically login the first time 
			}
			else {
				this.gui.executeJS("$('#fail').text('Something went wrong while trying to register.').fadeIn('slow');");
			}
		}
		catch(RemoteException e) {
			this.gui.executeJS("$('#fail').text('Communication error, try again later.').fadeIn('slow');");
		}
	}

	@Override
	public void login(String username, String password) {
		try {
			if(this.rmiServer.login(username, password, this.exportedRmiCallback)) {
				this.rmiCallback.setUsername(username);
				this.inviteManager.setUsername(username);
				this.gui.openPage("main.html");
				this.gui.setTitle("TextTwist - Home");
				this.gui.setLogoutOnClose(this.rmiServer, username); // Logout if we close the window
				this.gui.bindJavaObject("myUsername", username.toLowerCase());
			}
			else {
				this.gui.executeJS("$('#fail').text('Username e/o password errati.').fadeIn('slow');");
			}
		}
		catch(RemoteException e) {
			e.printStackTrace();
			this.gui.executeJS("$('#fail').text('Errore di comunicazione, riprova più tardi.').fadeIn('slow');");
		}
	}

	@Override
	public void logout() {
		try {
			if(this.rmiServer.logout(this.rmiCallback.getUsername())) {
				this.gui.openPage("splash.html");
			}
		}
		catch(RemoteException e) {
			e.printStackTrace(); // ???
		}
	}

	@Override
	public boolean sendInvites(String data) { // Chiamata da JavaScript con parametro lista degli utenti
		// Split la lista
		// Dalla all'invite manager
		// Fai partire l'invite manager
		// Restituisci il risultato dell'invite manager
		String[] players = data.split(System.lineSeparator()); // Non saranno lowercase
		ArrayList<String> playersToInvite = new ArrayList<>(Arrays.asList(players));
		if(playersToInvite.isEmpty() || playersToInvite.contains(this.rmiCallback.getUsername())) {
			return false; // Non posso giocare da solo
		}
		
		while(!this.inviteManager.addInvites(playersToInvite));
		try {
			Object result = this.executor.submit(this.inviteManager).get();
			if(result == null) { // Everything went okay
				return true;
			}
			else {
				this.gui.bindJavaObject("usersOffline", (String[]) result);
				return false;
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (ExecutionException e) {
			e.printStackTrace();
		}
		return false;
	}

	@Override
	public void submitWords(String words) {
		String[] wordsArray = words.split(System.lineSeparator());
		ArrayList<String> wordsList = new ArrayList<>(new HashSet<String>(Arrays.asList(wordsArray))); // Remove duplicates
		wordsList.removeAll(Collections.singleton("")); // Removes empty words / empty lines
		this.currentGame.addWords(wordsList);		
	}

	public void addInvite(String creator, String multicastIP) {
		this.inviteManager.addInvite(creator, multicastIP);
		if(this.gui.currentUrl().endsWith("invites.html")) { // If we have the invites list open... 
			showInvites(); // ... force its refresh
		}
	}
	
	@Override
	public void showInvites() {
		LinkedList<Pair<String, String>> invites;
		synchronized(this.inviteManager.getPending()) {
			invites = new LinkedList<>(this.inviteManager.getPending()); // It's a copy!
			// Synchronized vero! Devo avere due liste separate e distinte, prima non era realmente così!
		}
		this.gui.bindJavaObject("invites", invites);
		if(!invites.isEmpty())
			this.gui.bindJavaObject("pendingInvites", true);
		else
			this.gui.bindJavaObject("pendingInvites", false);
		this.gui.openPage("invites.html");
	}
	
	public void refuseInvite(String creator) {
		while(!this.inviteManager.refuse(creator));
		try {
			if((Boolean) this.executor.submit(this.inviteManager).get() == true) {
				showInvites();
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (ExecutionException e) {
			e.printStackTrace();
		}
	}
	
	public void removeInvite(String creator, String multicastIP) {
		this.inviteManager.removeInvite(creator, multicastIP);
		if(this.gui.currentUrl().contains("invites.html")) {
			// Nella pagina degli inviti eseguiamo un javascript per dare effetto, senza dare noia con il refresh della pagina
			this.gui.executeJS("var inv = Array.from(document.getElementsByClassName('btn-success'));"
					+ "inv.forEach(function(item, index) { if(item.dataset.user == '"+creator+"') { item.disabled = true;"
					+ "item.parentNode.getElementsByClassName('btn-danger')[0].disabled = true;"
					+ "var small = document.createElement('small'); small.innerHTML = '&nbsp;&mdash; EXPIRED';"
					+ "item.parentNode.getElementsByTagName('h2')[0].appendChild(small); }});");
		}
		// Altrimenti non c'è bisogno di fare niente perché non ero nella pagina degli inviti
	}

	@Override
	public void startMatch(String creator, String multicast) {
		while(!this.inviteManager.accept(creator));
		try {
			Future<Object> inviteTask = this.executor.submit(this.inviteManager);
			if(inviteTask.get() instanceof SocketChannel) { // Ho accettato con successo (controlla anche il null)
				// Posso far partire il gioco
				System.out.println("Ho accettato!");
				System.out.flush();
				this.currentGame = new Game(this.config, (SocketChannel) inviteTask.get(), multicast, this.gui, this.inviteManager.getUsername(), creator);
				this.currentGameThread = this.executor.submit(this.currentGame);
				System.out.println("Partito il game");
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		} catch (ExecutionException e) {
			e.printStackTrace();
		}
	}
	
	@Override
	public void resetSize() {
		this.gui.resize(300, 500);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public void getGlobalChart() {
		HashMap<String, Integer> chart;
		try {
			SocketChannel channel;
			if(this.config.getServerAddress().equalsIgnoreCase("localhost") || this.config.getServerAddress().equals("127.0.0.1")) {
				channel = SocketChannel.open(new InetSocketAddress(InetAddress.getLocalHost(), this.config.getTCPPort()));
			}
			else {
				channel = SocketChannel.open(new InetSocketAddress(InetAddress.getByName(this.config.getServerAddress()), this.config.getTCPPort()));
			}
			channel.configureBlocking(true);
			ByteBuffer bigBuffer = ByteBuffer.allocate(8192);
			bigBuffer.put("[CHART]".getBytes()).flip();
			channel.write(bigBuffer);
			bigBuffer.clear();
			channel.read(bigBuffer);
			try(ByteArrayInputStream bais = new ByteArrayInputStream(bigBuffer.array(), 0, bigBuffer.position());
					ObjectInputStream ois = new ObjectInputStream(bais);) {
				chart = (HashMap<String, Integer>) ois.readObject();
				System.out.println("Ho ricevuto la classifica che è:\n"+ chart);
			}
			catch(IOException e) {
				e.printStackTrace();
				return;
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
				return;
			}
		} catch (IOException e) {
			e.printStackTrace();
			return;
		} 
		// Ho bisogno di un array di stringhe e uno di int, li associo a mano
		String keys[] = new String[chart.size()];
		Integer values[] = new Integer[chart.size()];
		int i = 0;
		for(Map.Entry<String, Integer> entry : sortMapByValue(chart).entrySet()) { // It's a chart, so sort it!
			keys[i] = entry.getKey().substring(0, 1).toUpperCase() + entry.getKey().substring(1);
			values[i] = entry.getValue();
			i++;
		}
		
		this.gui.bindJavaObject("chartNames", keys); 
		this.gui.bindJavaObject("chartPoints", values);
		this.gui.openPage("chart.html");
	}
	
	@Override
	public void stopGame() { // Called when the timeout has expired and the use has not clicked "Start!" yet.
		this.currentGameThread.cancel(true);
	}
	
	/*
	 * Utility function that sorts a Map by its values (used to sort the chart)
	 */
	public static <K extends Comparable<? super K>, V extends Comparable<? super V>> Map<K, V> sortMapByValue(Map<K, V> map) {
		List<Map.Entry<K, V>> list = new LinkedList<>(map.entrySet()); // Map -> List
		Collections.sort(list, new Comparator<Map.Entry<K, V>>() { // Order the list
			@Override
			public int compare(Map.Entry<K, V> o1, Map.Entry<K, V> o2) {
				int comp = o1.getValue().compareTo(o2.getValue());
				if(comp == 0)
					return o1.getKey().compareTo(o2.getKey());
				return -comp; // Descending Order!
			}
		});
		Map<K, V> result = new LinkedHashMap<>(); // List -> Linked Hash Map for keeping order
		for(Map.Entry<K, V> entry : list) {
			result.put(entry.getKey(), entry.getValue());
		}
		return result;
	}
}
