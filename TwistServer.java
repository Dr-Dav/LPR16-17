package com.texttwist.server;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.rmi.NoSuchObjectException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.texttwist.client.RemoteUser;

public class TwistServer {
	private HashMap<String, RemoteUser> callbacks; // Username in lowercase!!
	private RMIService rmi;
	private ByteBuffer tcpBuf;
	private ByteBuffer udpBuf;
	private ServerSocketChannel mainChannel;
	private DatagramChannel udpChannel;
	private Selector selector;
	private ExecutorService executor;
	private Map<String, Match> matches;
	private LinkedList<SocketAddress> toAck;
	
	/* Condiviso fra i Match */
	private StorageService fileManager;
	private WordDictionary dictionary;
	private HashSet<String> multicastIPs;
	
	public TwistServer(ServerConfiguration config) throws RemoteException, IOException {
		this.callbacks = new HashMap<>();
		this.fileManager = new StorageService(config.getBaseDir() + "/res/", config.getMaxUserCacheDimension());
		
		this.rmi = new RMIService(this.callbacks, this.fileManager);
		LocateRegistry.getRegistry().rebind(RMITwist.NAME, this.rmi);
		
		this.tcpBuf = ByteBuffer.allocate(1024);
		this.mainChannel = ServerSocketChannel.open();
		this.mainChannel.bind(new InetSocketAddress(InetAddress.getLocalHost(), config.getTCPPort()));
		this.mainChannel.configureBlocking(false);
		
		this.udpBuf = ByteBuffer.allocate(1024);
		this.udpChannel = DatagramChannel.open();
		this.udpChannel.bind(new InetSocketAddress(InetAddress.getLocalHost(), config.getUDPPort()));
		this.udpChannel.configureBlocking(false);
		
		this.selector = Selector.open();
		this.mainChannel.register(this.selector, SelectionKey.OP_ACCEPT, this.tcpBuf);
		this.udpChannel.register(this.selector, SelectionKey.OP_READ, this.udpBuf);
		
		this.toAck = new LinkedList<>();
		
		this.executor = Executors.newFixedThreadPool(config.getMaxActiveMatches()); // DA CAMBIARE CON CONFIGURAZIONE
		
		this.matches = Collections.synchronizedMap(new HashMap<>());
		
		this.dictionary = new WordDictionary(config.getBaseDir() + "/res/dictionary.txt");
		
		this.multicastIPs = new HashSet<>();
	}
	
	@SuppressWarnings("unchecked")
	public void start() {
		ByteBuffer ackBuf = ByteBuffer.wrap("[ACK]".getBytes());
		while(true) {
			this.selector.selectedKeys().clear();
			try {
				this.selector.select();
				Set<SelectionKey> selectedKeys = this.selector.selectedKeys();
				
				for(SelectionKey key : selectedKeys) {
					if(key.isAcceptable()) { // Standard accept
						ServerSocketChannel ch = (ServerSocketChannel) key.channel();
						SocketChannel client = ch.accept();
						client.configureBlocking(false);
						client.register(this.selector, SelectionKey.OP_READ, ByteBuffer.allocate(1024));
					}
					if(key.isReadable() && key.channel() instanceof SocketChannel) { // TCP Connection
						SocketChannel client = (SocketChannel) key.channel();
						Object attachment = key.attachment();
						if(attachment instanceof ByteBuffer) {
							// It's the first time we enter here with this client, so it has to be a NEW request
							ByteBuffer msg = (ByteBuffer) attachment;
							msg.clear();
							client.read(msg);
							String[] request = new String(msg.array(), 0, msg.position()).split(" ");
							
							// ATTENZIONE! POTREI LEGGERE TUTTO INSIEME!
							if(request[0].equals("[NEWGAME]")) { // OK! Altrimenti sequenza malformata
								String username = request[1];
								System.out.println("Ricevuta richiesta da "+username);
								LinkedHashSet<String> players = new LinkedHashSet<>(); // Per rimuovere duplicati
								boolean finished = false;
								try {
									for(String item : Arrays.copyOfRange(request, 2, request.length)) {
										if(!item.equals("[END]")) {
											players.add(item.toLowerCase()); // Li aggiungo lower case!
											System.out.println(username+" ha invitato "+item);
										}
										else {
											finished = true;
										}
									}
								}
								catch(ArrayIndexOutOfBoundsException e) {
									// 2 > request.length, ancora non sono arrivati, non devo fare niente
								}
								if(finished) {
									// Ricevuti già tutti i giocatori, non devo fare altro!
									// Finished getting request
									key.cancel(); // Per ora lo fermo - prima di mandarlo al nuovo thread!
									Match match = new Match(username.toLowerCase(), players, (SocketChannel) key.channel(), this.callbacks, this.dictionary, this.multicastIPs, this.fileManager, this.matches);
									this.matches.put(username.toLowerCase(), match);
									this.executor.submit(match);
									System.out.println("Fineee!");
									continue; // Per non andare nel write
								}
								else {
									// Ci tornerò dopo con i prossimi utenti
									key.attach(new Triple<ByteBuffer, String, LinkedHashSet<String>>(msg, username, players));
								}
							}
							else if(request[0].equals("[ACCEPT]")){ // [1] = creator, [2] = player
								key.cancel();
								this.matches.get(request[1].toLowerCase()).addUserChannel(request[2], (SocketChannel) key.channel());
								continue;
							}
							else if(request[0].equals("[REFUSE]")) { // [1] = creator, [2] = player
								key.cancel();
								this.matches.get(request[1].toLowerCase()).addRefuse(request[2]);
								continue;
							}
							else if(request[0].equals("[CHART]")) {
								System.out.println("Qualcuno vuole la classifica! Che è:\n"+this.fileManager.getGlobalChart());
								ByteArrayOutputStream baos = new ByteArrayOutputStream();
								try(ObjectOutputStream oos = new ObjectOutputStream (baos);) {
									oos.writeObject(this.fileManager.getGlobalChart());
									oos.flush();
									//this.resultChannel.write(ByteBuffer.wrap(baos.toByteArray()));
								}
								catch(IOException e) {
									e.printStackTrace();
									key.cancel();
									continue;
								}
								key.attach(ByteBuffer.wrap(baos.toByteArray()));
								baos.close();
								key.interestOps(SelectionKey.OP_WRITE); // Non ho bisogno di specificare altro perché su TCP scrivo solo la hashmap
								continue;
							}
						}
						else if(attachment instanceof Triple<?, ?, ?>) {
							// The client is sending the invites
							ByteBuffer msg = (ByteBuffer) ((Triple<ByteBuffer, String, LinkedHashSet<String>>) attachment).getFirst();
							String username = (String) ((Triple<ByteBuffer, String, LinkedHashSet<String>>) attachment).getSecond();
							LinkedHashSet<String> players = (LinkedHashSet<String>) ((Triple<ByteBuffer, String, LinkedHashSet<String>>) attachment).getThird();
							
							msg.clear();
							client.read(msg);
							String[] request = new String(msg.array(), 0, msg.position()).split(" ");
							if(request[0].equals("[END]")) { // Per caso mi era mancato solo l'end
								key.cancel(); // Per ora lo fermo - prima di mandarlo al nuovo thread!
								Match match = new Match(username.toLowerCase(), players, (SocketChannel) key.channel(), this.callbacks, this.dictionary, this.multicastIPs, this.fileManager, this.matches);
								this.matches.put(username.toLowerCase(), match);
								this.executor.submit(match);
								System.out.println("Fineeee!");
								continue; // Per non andare nel write
							}
							else {
								boolean finished = false;
								for(String item : request) {
									if(!item.equals("[END]")) {
										players.add(item.toLowerCase());
										System.out.println(username+" ha invitato "+item);
									}
									else {
										finished = true;
									}
								}
								if(finished) {
									// Ricevuti già tutti i giocatori, non devo fare altro!
									// Finished getting request
									// Avviare la partita, chiamare i players ecc...
									key.cancel(); // Per ora lo fermo - prima di mandarlo al nuovo thread!
									Match match = new Match(username.toLowerCase(), players, (SocketChannel) key.channel(), this.callbacks, this.dictionary, this.multicastIPs, this.fileManager, this.matches);
									this.matches.put(username.toLowerCase(), match);
									this.executor.submit(match);
									System.out.println("Fine");
									continue; // Per non andare nel write
								}
								else {
									// Ci tornerò dopo con i prossimi utenti (NON DEVO FARE NIENTE)
									// key.attach(new Triple<ByteBuffer, String, ArrayList<String>>(msg, username, players));
								}
							}
						}
					}
					else if(key.isReadable() && key.channel() instanceof DatagramChannel) { // UDP Connection
						DatagramChannel channel = (DatagramChannel) key.channel();
						ByteBuffer buf = (ByteBuffer) key.attachment();
						buf.clear();
						SocketAddress client = channel.receive(buf);
						// Ricevo
						// [WORD] {creator} {me} {word}
						String[] content = new String(buf.array(), 0, buf.position()).split(" "); 
						if(content[0].equals("[WORD]")) { // OKAY
							// [1] = creator, [2] = player, [3] = word
							Match currentMatch = matches.get(content[1].toLowerCase());
							if(currentMatch.addWord(content[2], content[3])) {
								// Manda l'ACK
								// Registro l'indirizzo a cui mandare l'ACK
								this.toAck.add(client);
								key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
								
								System.out.println("Ricevuta parola "+content[3]);
							}
						}
						else if(content[0].equals("[END]")) { // Un client ha finito le parole
							Match currentMatch = matches.get(content[1]);
							System.out.println("Ricevuto un [end] con: " +content[0] + " "+ content[1] + " " + content[2]);
							System.out.println("Il server gira sul thread "+ Thread.currentThread());
							currentMatch.userCompleted(content[2]);
							
						}
					}
					if(key.isWritable() && key.channel() instanceof DatagramChannel) { // Write on UDP
						DatagramChannel channel = (DatagramChannel) key.channel();
						// Buffer ce l'ho già
						Iterator<SocketAddress> it = this.toAck.iterator();
						while(it.hasNext()) {
							channel.send(ackBuf, it.next()); // it.next() è già il SocketAddress
							it.remove(); // Li rimuovo mano mano che li scrivo
							ackBuf.flip(); // Per la successiva scrittura
						}
						// Quando ho finito tolgo OP_WRITE
						key.interestOps(SelectionKey.OP_READ);
					}
					else if(key.isWritable() && key.channel() instanceof SocketChannel) { // Write chart on TCP
						SocketChannel channel = (SocketChannel) key.channel();
						ByteBuffer buf = (ByteBuffer) key.attachment();
						System.out.println("Limit: "+buf.limit());
						channel.write(buf);
						System.out.println("Ho scritto "+buf.position());
						if(!buf.hasRemaining()) {
							key.cancel();
							channel.close();
						}
					}
				}
			}
			catch(ClosedSelectorException e) {
				// The server is closing!
				return;
			}
			catch(IOException e) {
				e.printStackTrace();
				return;
			}
		}
	}
	
	public void exit() {
		// Remove the RMI Stub from the registry
		try {
			LocateRegistry.getRegistry().unbind(RMITwist.NAME);
		} catch (RemoteException | NotBoundException e) {
			e.printStackTrace();
		}
		// Unexport the RMI Stub
		try {
			UnicastRemoteObject.unexportObject(this.rmi, true);
		} catch (NoSuchObjectException e) {
			e.printStackTrace();
		}
		// Shutdown the executor
		try {
			this.executor.shutdown(); // Not "now" beceause it can halt the matches if we interrupt them
			System.out.print("Waiting for the active matches to finish...");
			this.executor.awaitTermination(7, TimeUnit.MINUTES);
			System.out.println(" finished.");
		}
		catch(InterruptedException e) {
			e.printStackTrace();
		}
		// Close the selector (also wakes it up and should perform another for loop on the keys)
		// After the matches, so they can still use the channels
		try {
			this.selector.close();
			this.mainChannel.close();
			this.udpChannel.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		this.fileManager.close(); // Close it after the matches finish
	}
}
