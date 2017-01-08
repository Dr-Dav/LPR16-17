package com.texttwist.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import com.texttwist.client.Pair;
import com.texttwist.client.RemoteUser;

import java.rmi.RemoteException;

public class Match implements Runnable {
	private String creator; // NON E' LOWERCASE
	private LinkedHashSet<String> players; // Sono già lowercase
	private SocketChannel creatorChannel; // Per mandare l'ok o l'errore dopo aver chiamato le varie callback ma non la propria
	private DatagramChannel resultChannel; // Multicast per mandare i risultati
	private HashMap<String, Integer> scores; // Risultati di questa partita (da inviare con serializzazione!)
	private HashMap<String, HashSet<String>> words;
	private String multicastGroup;
	private ScheduledThreadPoolExecutor timer;
	private boolean timeExpired; // Non importa che sia atomic perché tanto ho bisogno del lock
	private Runnable timerTask;
	
	/* Sincronizzazione con thread principale per ricevere accept / refuse / words */
	private ReentrantLock lock;
	private Condition waiting;
	private HashMap<String, SocketChannel> wordChannels; // Per mandare le lettere (impostato dal thread principale quando riceve [ACCEPT]
	private int completedUsers;
	private LinkedList<String> completedUsernames; // Per calcolare subito il proprio risultato
	private ArrayList<String> refused;
	
	/* Condivise col thread principale e gli altri match */
	private HashMap<String, RemoteUser> callbacks;
	private WordDictionary dictionary;
	private HashSet<String> multicastIPs;
	private StorageService chartService;
	private Map<String, Match> currentMatches;
	
	
	public Match(String creator, LinkedHashSet<String> players, SocketChannel creatorChannel,
				 HashMap<String, RemoteUser> callbacks, WordDictionary dictionary,
				 HashSet<String> multicastIPs, StorageService fileManager, Map<String, Match> currentMatches) {
		this.creator = creator;
		this.players = players;
		this.callbacks = callbacks;
		this.dictionary = dictionary;
		this.multicastIPs = multicastIPs;
		this.chartService = fileManager;
		this.currentMatches = currentMatches;
		
		this.wordChannels = new HashMap<>();
		this.words = new HashMap<>();
		this.completedUsernames = new LinkedList<>();
		this.scores = new HashMap<>();
		this.refused = new ArrayList<>();
		
		this.creatorChannel = creatorChannel;
		try {
			// Lo rivoglio blocking
			this.creatorChannel.configureBlocking(true);
		}
		catch(IOException e) {
			e.printStackTrace();
		}
		
		this.multicastGroup = generateMulticastGroup(); // Prepare the multicast group for this match
		try {
			this.resultChannel = DatagramChannel.open().bind(null).connect(new InetSocketAddress(InetAddress.getByName(this.multicastGroup), 8000));
		} catch (IOException e) {
			this.resultChannel = null;
			e.printStackTrace();
		} 
		
		this.lock = new ReentrantLock();
		this.waiting = this.lock.newCondition();
		this.completedUsers = 0;
		
		this.timer = new ScheduledThreadPoolExecutor(1);
		this.timeExpired = false;
		this.timerTask = new Runnable() {
			@Override
			public void run() {
				System.out.println("Tempo scaduto!");
				lock.lock(); // Lock necessario per fare signal
				try {
					timeExpired = true;
					waiting.signal(); // Per svegliarmi
				}
				finally {
					lock.unlock();
				}
			}
		};
	}
	
	public void addUserChannel(String username, SocketChannel channel) {
		this.lock.lock();
		try {
			channel.configureBlocking(true); // Lo rimetto blocking
			this.wordChannels.put(username.toLowerCase(), channel); // LOWERCASE!
			System.out.println("Ha confermato "+username);
			if(this.wordChannels.size() + this.refused.size() == this.players.size() + 1)
				this.waiting.signal(); // Risveglio questo thread che stava aspettando i channels per mandare le lettere
		} catch (IOException e) {
			e.printStackTrace();
		}
		finally {
			this.lock.unlock();
		}
	}
	
	
	public void addRefuse(String who) { // Chiamato solo quando qualcuno rifiuta
		this.lock.lock();
		try {
			this.refused.add(who);
			System.out.println(who + " ha rifiutato.");
			if(this.wordChannels.size() + this.refused.size() == this.players.size() + 1)
				this.waiting.signal(); // ecco perché mi serve il lock
		}
		finally {
			this.lock.unlock();
		}
	}
	
	public boolean addWord(String player, String word) {
		// No need to sync
		// SECURITY CHECK -- AVOID INCONSITENCY IF SOMEONE SENDS A WORD OUT OF TIME
		if(this.completedUsers == this.players.size() + 1) // Avoid modifying if everything was already fine
			return false;
		
		if(this.words.containsKey(player.toLowerCase())) {
			return this.words.get(player.toLowerCase()).add(word);
		}
		else {
			HashSet<String> wordSet = new HashSet<>();
			wordSet.add(word);
			return this.words.put(player.toLowerCase(), wordSet) == null; // No previous associations
		}
	}
	
	public void userCompleted(String user) {
		System.out.println("E io che modifico le parole sono il thread "+Thread.currentThread());
		this.lock.lock();
		try {
			this.completedUsers++;
			this.completedUsernames.addLast(user.toLowerCase());
			if(this.completedUsers == this.players.size() + 1) {
				System.out.println("Fatto tutto!");
			}
			this.waiting.signal(); // Risveglio questo thread
		}
		finally {
			this.lock.unlock();
		}
	}
	
	private String generateMulticastGroup() {
		ThreadLocalRandom rnd = ThreadLocalRandom.current();
		// Da 225.0.0.0 a 239.255.255.255
		String multicast;
		synchronized(this.multicastIPs) {
			do {
				multicast = rnd.nextInt(225, 240) + "." + rnd.nextInt(256) + "." + rnd.nextInt(256) + "." + rnd.nextInt(256);
				if(this.multicastIPs.add(multicast)) 
					return multicast;
			}
			while(this.multicastIPs.contains(multicast));
		}
		return null;
	}
	
	private void destroy() {
		System.err.println("Destroy!");
		// Rilascio il multicast group
		// Chiudo il creator channel
		// Chiudo il multicast channel
		try {
			/* Don't care if they already have been closed */
			this.resultChannel.close();
			this.creatorChannel.close();
			for(SocketChannel socket : this.wordChannels.values()) {
				socket.close();
			}
			this.timer.shutdownNow(); // Spegne il timer
		}
		catch(IOException e) {
			e.printStackTrace();
		}
		synchronized(this.multicastIPs) {
			this.multicastIPs.remove(this.multicastGroup);
		}
		// Remove me from the current active matches 
		// No need to synchronize because it's a Synchronized Map
		this.currentMatches.remove(this.creator.toLowerCase());
	}
	
	@Override
	public void run() {
		// Prima di tutto, inoltrare gli inviti agli altri giocatori
		// Ce li ho dentro i callback. Se non ci sono, o prendo RemoteException -> Offline / Non registrati
		// Devo fare synchronized
		boolean creatorCanceled = false;
		String errorMsg = "[ERROR-OFFLINE] ";
		
		for(String playerToInvite : this.players) {
			synchronized(this.callbacks) {
				// Prima di inoltrare gli inviti devo controllare che ci siano tutti, altrimenti poi dovrei cancellare manualmente gli inviti
				if(!this.callbacks.containsKey(playerToInvite)) { // Fine-grained
					errorMsg = errorMsg.concat(playerToInvite + " ");
				}
			}
		}
		if(!errorMsg.equals("[ERROR-OFFLINE] ")) { // Almeno un utente era offline
			// Mando solo l'errore al creator senza inoltrare gli inviti agli altri.
			try {
				this.creatorChannel.write(ByteBuffer.wrap(errorMsg.getBytes()));
			} catch (IOException e1) {
				e1.printStackTrace();
			}
			this.destroy();
			return;
			
		}
		else { // Tutti gli utenti sono online
			for(String playerToInvite : this.players) {
				try {
					this.callbacks.get(playerToInvite).invite(this.creator, this.multicastGroup);
				}
				catch(RemoteException | NullPointerException e) {
					// Un utente è offline - chiudo tutto! E non chiamo e nemmeno gli altri, tanto è inutile!
					// Manda errore al creator
					// Chiudi tutto
					// return
					try {
						this.creatorChannel.write(ByteBuffer.wrap(("[ERROR-OFFLINE] " + playerToInvite).getBytes()));
					} catch (IOException e1) {
						e1.printStackTrace();
					}
					this.destroy();
					return;
				}
			}
		}

		// Se arrivo qui, ho inoltrato correttamente tutti gli inviti.
		// Devo confermare l'inoltro al creator e chiamare la sua callback -- e chiudere anche il suo TCP
		try {
			this.creatorChannel.write(ByteBuffer.wrap("[CONFIRM]".getBytes()));
			synchronized(this.callbacks) {
				this.callbacks.get(creator.toLowerCase()).invite(creator, this.multicastGroup); // I callbacks sono lowercase
			}
			this.creatorChannel.close(); // Chiudo TCP come da specifiche
		}
		catch(RemoteException | NullPointerException e) {
			creatorCanceled = true;
			// Non faccio niente, il creator prenderà 0 punti!
			// Il creator è offline, devo uscire -- SISTEMARE COSA FACCIO CON CHI AVEVO PRECEDENTEMENTE INVITATO
		}
		catch(IOException e) {
			e.printStackTrace(); // Però potrebbe averlo chiuso, che faccio?
		} // SECONDO TRY
		
		/*
		 * Arrivato qui, ho inoltrato correttamente tutti gli inviti.
		 * Il thread principale si occuperà di ricevere gli accept / refuse
		 */
		// this.cancel() -- qualcuno ha rifiutato
		// this.addUserChannel -- qualcuno ha accettato
		System.out.println("Ho confermato al creator");
		
		// Prima di sospendermi, attivo il timer dei 7 minuti (per ora 30 secondi)
		this.timer.schedule(this.timerTask, 7, TimeUnit.MINUTES);
		
		this.lock.lock();
		try {
			while(this.wordChannels.size() + this.refused.size() != this.players.size() + (creatorCanceled ? 0 : 1) && !this.timeExpired) { // +1 per il creator
				// Aspetto finché non accettano/rifiutano tutti o scade il tempo
				try {
					this.waiting.await();
				}
				catch(InterruptedException e) {
					this.destroy();
					return;
				}
			}
		}
		finally {
			this.lock.unlock();
		}
		
		// Qui devo controllare che non sia scaduto il tempo:
		this.lock.lock(); // Previene l'eventuale modifica da parte del timer
		try {
			if(this.timeExpired) {
				// Tempo scaduto, devo cancellare la partita e mandare errore a tutti quelli che avevano accettato
				ByteBuffer errorBuf = ByteBuffer.wrap("[ERROR-TIMEOUT]".getBytes());
				// Inoltro l'errore a tutti quelli che avevano accettato
				for(SocketChannel channel : this.wordChannels.values()) {
					try {
						System.out.println("Scrivo timeout a "+channel);
						channel.write(errorBuf);
						channel.close();
						errorBuf.flip(); // Per le successive scritture!
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				// Devo cancellare l'invito a chi l'aveva ignorato
				for(String player : this.players) {
					if(!this.wordChannels.containsKey(player)) { // Non aveva accettato
						try {
							synchronized(this.callbacks) {
								this.callbacks.get(player).removeInvite(this.creator, this.multicastGroup);
								System.out.println("chiamata callback di "+player);
							}
						}
						catch(RemoteException | NullPointerException e) {
							e.printStackTrace(); // Se nel frattempo è andato offline pazienza!
						}
					}
				}
				this.destroy();
				return;
			}
			else {
				System.out.println("Fermo il timer");
				this.timer.remove(this.timerTask); // Ferma il timer
				this.timer.purge();
			}
		}
		finally {
			this.lock.unlock();
		}
		// Qui vuol dire che ho tutti i channels per mandare le lettere (o qualcuno ha rifiutato)
		if(this.refused.size() != 0) {
			// qualcuno ha rifiutato, devo interrompere la partita e avvisare tutti quelli che hanno accettato
			errorMsg = "[ERROR-REFUSED] ";
			for(String who : this.refused) {
				errorMsg = errorMsg.concat(who + " ");
				System.out.println("AAA: "+who);
			}
			
			System.out.println("Rifiutato: "+errorMsg);
			
			ByteBuffer errorBuf = ByteBuffer.wrap(errorMsg.getBytes());
			// Scrivo errore a chi aveva accettato e cancello la partita
			// Il creator avrà sempre accettato!
			for(SocketChannel channel : this.wordChannels.values()) {
				try {
					System.out.println("Scrivo errore a "+channel);
					channel.write(errorBuf);
				} catch (IOException e) {
					e.printStackTrace();
				}
				errorBuf.flip();
			}
			// Devo cancellare l'invito a chi l'aveva ignorato
			for(String player : this.players) {
				if(!this.wordChannels.containsKey(player)) { // Non aveva accettato
					try {
						synchronized(this.callbacks) {
							this.callbacks.get(player).removeInvite(this.creator, this.multicastGroup);
							System.out.println("chiamata callback di "+player);
						}
					}
					catch(RemoteException | NullPointerException e) {
						e.printStackTrace(); // Se nel frattempo è andato offline pazienza!
					}
				}
			}
			this.destroy();
			return; // Close this match
		}
		
		String letters = WordDictionary.shuffle(this.dictionary.getFirstWord());
		ByteBuffer lettersBuf = ByteBuffer.wrap(letters.getBytes());
		System.out.println("Lettere: "+letters);
		// Inoltro le lettere a tutti
		for(SocketChannel channel : this.wordChannels.values()) {
			try {
				System.out.println("Scrivo le lettere a "+channel);
				channel.write(lettersBuf);
			} catch (IOException e) {
				e.printStackTrace(); // Se qualcuno ha chiuso pazienza
			}
			lettersBuf.flip();
		}
		
		// Ora aspetto 5 minuti per ricevere le parole (30 secondi)
		this.lock.lock();
		try {
			this.timeExpired = false; // Lo rimette a false, non si sa mai che lo abbia cambiato nel mentre
		}
		finally {
			this.lock.unlock();
		}
		this.timer.schedule(this.timerTask, 5, TimeUnit.MINUTES);
		
		// Ho inviato tutte le lettere, ora mi metto ad aspettare, massimo 5 minuti su UDP
		this.lock.lock();
		try { // REFACTOR ASSOLUTAMENTE
			while(this.completedUsers != this.players.size() + (creatorCanceled ? 0 : 1)) {
				while(this.completedUsernames.isEmpty() && !this.timeExpired) {
					System.out.println("Io che aspetto le parole sono il thread "+ Thread.currentThread());
					this.waiting.awaitUninterruptibly();
				}
			
				if(this.timeExpired) {
					break; // Esco dal ciclo e ignoro eventuali altre parole
				}
				else { // Ho un utente che ha finito
					String userWhoCompleted = this.completedUsernames.removeFirst();
					this.lock.unlock(); // Sblocco il lock per lasciare che altri possano mandare le loro parole
					// Calcolo il suo punteggio
					this.scores.put(userWhoCompleted, 0);
					for(String word : this.words.get(userWhoCompleted)) {
						System.out.println("Parola di "+userWhoCompleted + ": "+word);
						if(this.dictionary.checkWord(word, letters)) { 
							Integer currentScore = this.scores.get(userWhoCompleted);
							this.scores.put(userWhoCompleted, currentScore+WordDictionary.removeDuplicates(word).length());
						}
					}
					try(ByteArrayOutputStream baos = new ByteArrayOutputStream();
						ObjectOutputStream oos = new ObjectOutputStream(baos)) {
						
						oos.writeUnshared(new Pair<String, Integer>(userWhoCompleted, this.scores.get(userWhoCompleted)));
						oos.flush();
						this.resultChannel.write(ByteBuffer.wrap(baos.toByteArray()));

						// MANDA IL PUNTEGGIO A CHI DI DOVERE
					}
					catch(IOException e) {
						e.printStackTrace();
					}
					this.lock.lock();
				}
			
			
			}
		}
		finally {
			this.lock.unlock();
		}
		
		// Qui o il tempo è scaduto o ho ricevuto tutto, ma non fa differenza!
		this.timer.remove(this.timerTask);
		
		if(this.timeExpired) {
			// Qualcuno non ha mandato le parole in tempo e gli do zero punti
			for(String player : this.players) {
				this.scores.putIfAbsent(player, new Integer(0)); // Do zero solo a chi non avevo già inserito prima
				try(ByteArrayOutputStream baos = new ByteArrayOutputStream();
					ObjectOutputStream oos = new ObjectOutputStream(baos) ){
					
					oos.writeUnshared(new Pair<String, Integer>(player, 0));
					oos.flush();

					this.resultChannel.write(ByteBuffer.wrap(baos.toByteArray()));

				}
				catch(IOException e) {
					e.printStackTrace();
				}
			}
			if(this.scores.putIfAbsent(creator.toLowerCase(), 0) == null) { // Anche il creator non ha inviato le parole
				try(ByteArrayOutputStream baos = new ByteArrayOutputStream();
					ObjectOutputStream oos = new ObjectOutputStream(baos) ){
					
					oos.writeUnshared(new Pair<String, Integer>(creator.toLowerCase(), 0));
					oos.flush();

					this.resultChannel.write(ByteBuffer.wrap(baos.toByteArray()));

				}
				catch(IOException e) {
					e.printStackTrace();
				}
			}
		}
		
		try(ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ObjectOutputStream oos = new ObjectOutputStream(baos) ){
				
			oos.writeUnshared(new String("[END]"));
			oos.flush();
			this.resultChannel.write(ByteBuffer.wrap(baos.toByteArray()));
			System.out.println("Ho finito e ho scritto [END]");
		}
		catch(IOException e) {
			e.printStackTrace();
		}
		
		
		// Qui dovrei avere gli stessi risultati di prima
		
		System.out.println("Risultati: "+this.scores);
			
		this.chartService.storeMatchResults(this.scores);
			
		System.out.println("Yeee, partita finta!");
		
		this.destroy();
		
	} // RUN

}
