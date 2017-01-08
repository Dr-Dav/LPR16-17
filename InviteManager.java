package com.texttwist.client;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

public class InviteManager implements Callable<Object> {
	private AtomicBoolean refuse;
	private AtomicBoolean accept;
	private AtomicBoolean send;
	private ArrayList<String> playersToInvite;
	private String playerToAccept;
	private String playerToRefuse;
	private String myUsername;
	private ArrayList<Pair<String, String>> pendingInvites;
	
	private ClientConfiguration config;
	
	public InviteManager(ClientConfiguration config) {
		this.config = config;
		this.refuse = new AtomicBoolean();
		this.accept = new AtomicBoolean();
		this.send = new AtomicBoolean();
		this.playersToInvite = null;
		this.playerToAccept = null;
		this.playerToRefuse = null;
		this.pendingInvites = new ArrayList<>();
	}
	
	public void setUsername(String username) {
		this.myUsername = username;
	}
	
	public String getUsername() {
		return myUsername;
	}
	
	public void addInvite(String creator, String multicastIP) {
		System.out.println("Aggiunto invito: "+creator);
		synchronized(this.pendingInvites) { // To sync with call()
			this.pendingInvites.add(new Pair<String, String>(creator, multicastIP));
		}
	}
	
	public void removeInvite(String creator, String multicastIP) {
		System.out.println("Rimuovo: "+creator+ " ("+multicastIP+")");
		synchronized(this.pendingInvites) {
			this.pendingInvites.remove(new Pair<String, String>(creator, multicastIP));
		}
	}
	
	public ArrayList<Pair<String, String>> getPending() {
		return this.pendingInvites;
	}

	public boolean accept(String who) {
		if(this.accept.compareAndSet(false, true)) { // Funzionerà solo se non ero già impegnato
			this.playerToAccept = who;
			return true;
		}
		return false;
	}
	
	public boolean refuse(String who) {
		if(this.refuse.compareAndSet(false, true)) {
			this.playerToRefuse = who;
			return true;
		}
		return false;
	}
	
	public boolean addInvites(ArrayList<String> players) {
		if(this.send.compareAndSet(false, true)) {
			this.playersToInvite = players; // Lowercase lo fa già il server
			return true;
		}
		return false;
	}
	
	@Override
	public Object call() { // Restituisce o un boolean o la connessione
		try {
			/* Apro connessione e preparo buffer */
			SocketChannel server;
			if(this.config.getServerAddress().equalsIgnoreCase("localhost") || this.config.getServerAddress().equals("127.0.0.1")) {
				server = SocketChannel.open(new InetSocketAddress(InetAddress.getLocalHost(), this.config.getTCPPort()));
			}
			else {
				server = SocketChannel.open(new InetSocketAddress(InetAddress.getByName(this.config.getServerAddress()), this.config.getTCPPort()));
			}
			server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
			server.configureBlocking(true);
			ByteBuffer buf = ByteBuffer.allocate(1024);
			
			if(this.send.get()) {
				buf.put(new String("[NEWGAME] " + this.myUsername + " ").getBytes());
				buf.flip();
				server.write(buf);
				for(String player : this.playersToInvite) { // No need to synchronize because the AtomicBoolean will prevent any edits
					if(!player.isEmpty()) { // Ignore empty lines
						System.out.println("Ho beccato: "+player);
						buf.clear();
						buf.put(new String(player + " ").getBytes()); // Non ho bisogno di mandare [INVITE], mando direttamente gli username separati da spazio
						buf.flip();
						server.write(buf);
					}
				}
				
				server.write((ByteBuffer) ((ByteBuffer) buf.clear()).put("[END]".getBytes()).flip());
				
				// Ora arriverà [ERROR-OFFLINE] o [CONFIRM]
				
				buf.clear();
				server.read(buf);
				String response = new String(buf.array(), 0, buf.position());
				if(response.equals("[CONFIRM]")) { // Tutto ok
					this.send.set(false); // mette a false il send
					return null;
				}
				else if(response.startsWith("[ERROR-OFFLINE] ")) {
					System.out.println("At least one user is offline.");
					this.send.set(false); // Mette a false il send
					String[] usersWhoRefused = response.split(" ");
					return Arrays.copyOfRange(usersWhoRefused, 1, usersWhoRefused.length); // Resistuisce la lista di chi ha rifiutato
				}
				
				this.send.set(false); // Mette a false il send
				return null; // Should never happen
			}
			if(this.accept.get()) {
				// Devo mandare [ACCEPT] {creator} {player}
				// Devo cancellare tutti gli altri inviti
				// Prima cancello gli altri e poi accetto il mio
				synchronized(this.pendingInvites) {
					Iterator<Pair<String, String>> it = this.pendingInvites.iterator();
					while(it.hasNext()) {
						Pair<String, String> invite = it.next();
						if(!invite.getFirst().equals(this.playerToAccept)) { // Non devo rifiutare il mio!
							System.out.println("Ho rifiutato l'invito di "+invite.getFirst());
							buf.clear();
							buf.put(("[REFUSE] " + invite.getFirst() + " " + this.myUsername).getBytes()).flip();
							server.write(buf);
						}
						it.remove(); // Rimuove gli altri inviti
					}
				}
				
				buf.clear();
				buf.put(("[ACCEPT] " + this.playerToAccept + " " + this.myUsername).getBytes()).flip();
				server.write(buf);
				System.out.println("Ho accettato l'invito di "+this.playerToAccept);
				
				// C'è il problema che la connessione deve rimanere aperta per ricevere le lettere
				// In più si blocca misteriosamente quando si preme un invito -- per forza, non ho ancora fatto niente!
				
				this.accept.set(false); // Rimette a false
				return server;
			}
			if(this.refuse.get()) {
				buf.put(("[REFUSE] " + this.playerToRefuse + " " + this.myUsername).getBytes()).flip();
				server.write(buf);
				System.out.println("Ho rifiutato l'invito di "+this.playerToRefuse);
				synchronized(this.pendingInvites) {
					Iterator<Pair<String, String>> it = this.pendingInvites.iterator();
					while(it.hasNext()) {
						Pair<String, String> invite = it.next();
						if(invite.getFirst().equalsIgnoreCase(this.playerToRefuse))
							it.remove(); // Rimuove questo invito
					}
				}
				
				server.close();
				return this.refuse.getAndSet(false);
			}
		} // try
		catch(IOException e) {
			e.printStackTrace();
			return false;
		}
		return false;
	}

}
