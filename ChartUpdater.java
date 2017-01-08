package com.texttwist.server;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class ChartUpdater implements Runnable {
	private FileChannel channel;
	private LinkedList<HashMap<String, Integer>> pendingUpdates;
	private ReentrantLock lock;
	private Condition emptyList;
	private HashMap<String, Integer> globalChart;

	public ChartUpdater(HashMap<String, Integer> globalChart, FileChannel channel, LinkedList<HashMap<String, Integer>> pendingUpdates, ReentrantLock lock, Condition emptyList) {
		this.channel = channel;
		this.pendingUpdates = pendingUpdates;
		this.lock = lock;
		this.emptyList = emptyList;
		this.globalChart = globalChart;
	}
	
	@Override
	public void run() {
		// First, grap an update from the list:
	
		HashMap<String, Integer> current;
		ByteBuffer writeBuf = ByteBuffer.allocate(Integer.BYTES); // Scrivo solo un int
		ByteBuffer readBuf = ByteBuffer.allocate(2*Integer.BYTES + 30);
		int usernameLength;
		byte[] usernameBytes;
		
		// First, build the global chart (once!)
		// FUNZIONA!
		synchronized(this.globalChart) {
			synchronized(this.channel) {
				try {
					this.channel.position(0);
					while(this.channel.read(readBuf) != -1) {
						readBuf.flip();
						usernameLength = readBuf.getInt();
						usernameBytes = new byte[usernameLength];
						readBuf.get(usernameBytes, 0, usernameLength);
						this.globalChart.put(new String(usernameBytes, 0, usernameLength), readBuf.getInt());
						System.out.println("Letto: " + new String(usernameBytes, 0, usernameLength));
						readBuf.compact();
					}
					readBuf.flip(); // Deve stare fuori!
					while(readBuf.hasRemaining()) { // Potrei aver raggiunto la fine prima di aver scritto anche l'ultimo
						System.out.println("Remaining");
						usernameLength = readBuf.getInt();
						usernameBytes = new byte[usernameLength];
						readBuf.get(usernameBytes, 0, usernameLength);
						System.out.println("Letto nel remaining "+new String(usernameBytes, 0, usernameLength));
						this.globalChart.put(new String(usernameBytes, 0, usernameLength), readBuf.getInt());
						readBuf.compact().flip();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			System.out.println("Caricata classifica: "+this.globalChart);
			System.out.flush();
		}
		
		
		while(!Thread.currentThread().isInterrupted()) {
			this.lock.lock();
			try {
				while(this.pendingUpdates.size() == 0 && !Thread.currentThread().isInterrupted()) {
					System.out.println("Mi sospendo");
					this.emptyList.await();
				}
				System.out.println("Mi sveglio!");
				current = this.pendingUpdates.removeFirst();
				System.out.println("Current = "+current);
			}
			catch(InterruptedException e) {
				return; // Just quit
			}
			finally {
				this.lock.unlock();
			}
			
			// write(ByteBuffer, position) per scrivere alla fine
			// read(ByteBuffer, position) per leggere dall'inizio (ma forse è meglio position())
			int totalUsersRead = 0;
			int totalUsernamesLength = 0;
		
			readBuf.clear(); // Si sa mai 
			Set<String> toUpdate = current.keySet(); 
			try {
				synchronized(this.channel) { // Synchronize with StorageService.addNewUser()
					System.out.println("Synchronized this channel");
					this.channel.position(0); // Scorri all'inizio del file
					while(toUpdate.size() != 0) {
						System.out.println("while!");
						this.channel.read(readBuf);
						readBuf.flip();
						try {
							usernameLength = readBuf.getInt();
						}
						catch(BufferUnderflowException e) {
							// L'utente non era presente dentro chart.dat, va aggiunto.
							// Scrivo tutti gli username rimanenti:
							for(String username : toUpdate) {
								readBuf.clear(); // Uso il readbuf per scrivere
								// Preparo il contenuto da scrivere
								readBuf.putInt(username.getBytes().length).put(username.getBytes()).putInt(current.get(username));
								readBuf.flip();
								this.channel.write(readBuf, this.channel.size());
							}
							readBuf.clear();
							break;
						}
						System.out.println("UsernameLength: "+usernameLength);
						totalUsernamesLength += usernameLength; // Accumulo le varie lunghezze degli username
						usernameBytes = new byte[usernameLength];
						readBuf.get(usernameBytes, 0, usernameLength);
						String username = new String(usernameBytes, 0, usernameLength);
						
						if(current.containsKey(username)) { // Inefficiente?
							System.out.println("Ho trovato " +username+" in classifica!");
					
							int newValue = readBuf.getInt()+current.get(username).intValue();
							totalUsersRead++; // Ora che ho fatto il getInt ho letto tutto un utente
							
							writeBuf.clear();
							writeBuf.putInt(newValue); // Sommo il vecchio punteggio al nuovo
							
							System.out.println("NewValue: " + newValue);
							System.out.println("Position: " + this.channel.position());
							
							// NON TORNO INDIETRO MA SCRIVO CON write(buf, pos);
							
							// this.channel.position(this.channel.position() - Integer.BYTES); // Torno indietro di un int per aggiornare il punteggio
							writeBuf.flip();
							
							this.channel.write(writeBuf, 2 * Integer.BYTES * totalUsersRead + totalUsernamesLength - Integer.BYTES); // Scrivo il nuovo punteggio
							
							synchronized(this.globalChart) {
								this.globalChart.replace(username, newValue);
							}
							toUpdate.remove(username);
						}
						else {
							readBuf.getInt(); // Per non lasciarlo lì
							totalUsersRead++;
						}
						readBuf.compact(); // Compact importante, consente di leggere tutto per bene
					} // E torno a leggere
				}
			}
			catch(IOException e) {
				e.printStackTrace();
			}	
			catch(Throwable e) {
				e.printStackTrace();
			} 
		} // While
	}

}
