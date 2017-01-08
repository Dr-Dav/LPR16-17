package com.texttwist.server;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class StorageService {
	private RandomAccessFile[] usersFiles;
	private TreeSet<User> users; // To access logged users faster (User Cache)
	private String baseDir;
	private int maxCacheDim;
	
	private ExecutorService executor;
	private FileChannel chartChannel;
	private ByteBuffer registrationBuf; // Here to avoid allocating a new buffer each time a new user registers
	private ReentrantLock lock;
	private Condition waitOnQueue;
	private LinkedList<HashMap<String, Integer>> pendingUpdates; // FIFO
	private HashMap<String, Integer> globalChart; // Chart Cache
	
	public StorageService(String baseDir, int maxCacheDim) {
		this.baseDir = baseDir;
		this.usersFiles = new RandomAccessFile[27]; // They'll be null-ed by the JVM
		this.users = new TreeSet<>();
		this.maxCacheDim = maxCacheDim; // CONFIGURAZIONE!
		this.registrationBuf = ByteBuffer.allocate(2*Integer.BYTES + 30); // Username max = 30 bytes + 2 Integers
		try {
			this.chartChannel = FileChannel.open(Paths.get(this.baseDir + "chart.dat"),
											StandardOpenOption.READ,
											StandardOpenOption.WRITE,
											StandardOpenOption.CREATE);
		}
		catch(IOException e) {
			this.chartChannel = null;
			e.printStackTrace();
		}
		
		this.globalChart = new HashMap<>();
		
		this.lock = new ReentrantLock();
		this.waitOnQueue = this.lock.newCondition();
		this.pendingUpdates = new LinkedList<>();
		this.executor = Executors.newSingleThreadExecutor();
		this.executor.submit(new ChartUpdater(this.globalChart, this.chartChannel, this.pendingUpdates, this.lock, this.waitOnQueue));
		
	}
	
	/*
	 * The filesystem is split in 27 files: one for a-z letters and one for the other symbols.
	 * Returns the correct RAF reference based on the initial of the username
	 */
	private RandomAccessFile getUserFile(String username) {
		char firstChar = username.toLowerCase().charAt(0); // LOWERCASE
		if((firstChar >= 'a' && firstChar <= 'z')) {
			synchronized(this.usersFiles) {
				if(this.usersFiles[firstChar-97] == null) {
					try {
						this.usersFiles[firstChar-97] = new RandomAccessFile(this.baseDir + "users/" + firstChar + ".dat", "rw");
						return this.usersFiles[firstChar-97];
					} catch (FileNotFoundException e) {
						e.printStackTrace();
						return null;
					}
				}
				else {
					return this.usersFiles[firstChar-97];
				}
			}
		}
		else {
			synchronized(this.usersFiles) {
				if(this.usersFiles[26] == null) {
					try {
						this.usersFiles[26] = new RandomAccessFile(this.baseDir + "users/symbols.dat", "rw");
						return this.usersFiles[26];
					}
					catch(FileNotFoundException e) {
						e.printStackTrace();
						return null;
					}
				}
				else {
					return this.usersFiles[26];
				}
			}
		}
	}
	
	public boolean addNewUser(String username, String password) { // Registrazione
		if(username.contains(" ") || password.length() == 0) // Provide security at this level
			return false;
		
		User newUser = new User(username, password);
		synchronized(this.users) {
			if(this.users.contains(newUser))
				return false;
		}
		// Else, fallback to the file
		String line;
		try {
			System.out.println("Registro "+username);
			RandomAccessFile file = getUserFile(username); // Fa il lowercase
			username = username.toLowerCase();
			synchronized(file) {
				file.seek(0);
				while((line = file.readLine()) != null) {
					if(line.startsWith(username))
						return false;
				}
				// Se arrivo qui, non c'era e lo aggiungo
				file.write((username + " " + password + "\n").getBytes()); // Username è lowercase
			}
			System.out.println("Registrato "+username);
			synchronized(this.users) {
				this.users.add(newUser); // Add to the treeset to have faster future accesses
			}
			synchronized(this.chartChannel) {
				this.registrationBuf.clear();
				this.registrationBuf.putInt(username.getBytes().length)
							 .put(username.getBytes())
							 .putInt(0); // Zero points at registration time
				this.registrationBuf.flip();
				this.chartChannel.write(this.registrationBuf, this.chartChannel.size());
				System.out.println("Registrato utente con: " + Arrays.toString(this.registrationBuf.array()));
			}
			synchronized(this.globalChart) { // synch con RMI (?)
				this.globalChart.put(username, new Integer(0)); // Username lowercase
			}
			return true;
		}
		catch(IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	public boolean checkUser(String username, String password) { // Login
		User u = new User(username, password);
		synchronized(this.users) {
			if(this.users.contains(u)) // Checks the password too (because the password in this call won't be empty)
				return true;
		}
		// Else, fallback to the file
		String line;
		try {
			RandomAccessFile file = getUserFile(username);
			synchronized(file) {
				file.seek(0);
				username = username.toLowerCase();
				while((line = file.readLine()) != null) {
					if(line.startsWith(username) && line.substring(username.length()+1).equals(password)) {
						// Trovato, aggiungiamolo al TreeSet for further accesses
						synchronized(this.users) {
							this.users.add(u);
						}
						synchronized(this.globalChart) {
							if(this.globalChart.get(username) == null) { // Vuol dire che era stato cancellato dal file, ce lo devo rimettere
								this.globalChart.put(username, new Integer(0));
								// Me ne accorgerò solo al login, e poi per salvarlo deve fare almeno una partita
							}
						}
						return true;
					}
				}
			}
			return false; // L'utente non esiste
		}
		catch(IOException e) {
			e.printStackTrace();
			return false;
		}
	}
	
	/*
	 * Inform this system that an user has logged out and that it can be removed from the TreeSet to make room to other users.  
	 */
	public void suggestRemove(String username) {
		synchronized(this.users) {
			if(this.users.size() >= this.maxCacheDim - 5) { // 5 arbitrary
				this.users.remove(new User(username, "")); // Equals without password
			}
		}
	}
	

	public void storeMatchResults(HashMap<String, Integer> results) {
		/* Prima aggiorno tutta la cache */
		System.out.println("Store Match Results!");
		for(Map.Entry<String, Integer> entry : results.entrySet()) {
			synchronized(this.globalChart) {
				if(this.globalChart.containsKey(entry.getKey())) { // Se c'era già lo aggiorno
					this.globalChart.replace(entry.getKey(), this.globalChart.get(entry.getKey()) + entry.getValue());
				}
				else {
					this.globalChart.put(entry.getKey(), entry.getValue());
					// Perché suppongo che dentro la global chart abbia già tutta la classifica
					// (Diverso da prima)
				}
			}
		}
		System.out.println("Finito il for!");
		System.out.flush();
		/* Poi aggiorno il file */
		this.lock.lock();
		try {
			this.pendingUpdates.addLast(results);
			this.waitOnQueue.signal(); // Wake up the ChartUpdater thread
		}
		finally {
			this.lock.unlock();
		}
		
	}
	
	// Carico tutta la classifica in memoria....
	public HashMap<String, Integer> getGlobalChart() {
		synchronized(this.globalChart) {
			return this.globalChart;
		}
	}

	public void close() {
		this.executor.shutdownNow();
		try { 
			System.out.print("Waiting for ChartUpdater thread to finish... ");
			this.executor.awaitTermination(1, TimeUnit.MINUTES);
			System.out.println("finished.");
		}
		catch(InterruptedException e) {
			e.printStackTrace();
		}
		//synchronized(this.chartChannel) { // Wait for others to finish, anche se dovrebbero aver già finito
		try {
			this.chartChannel.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		//}
		// Close every RAF
		for(RandomAccessFile raf : this.usersFiles) {
			if(raf != null) {
				try {
					raf.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
}
