package com.texttwist.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.HashMap;

public class MulticastListener implements Runnable {
	
	private DatagramChannel channel;
	private GUI gui;
	
	public MulticastListener(DatagramChannel channel, GUI gui) {
		this.channel = channel;
		this.gui = gui;
	}
	
	@Override
	public void run() {
		HashMap<String, Integer> results = new HashMap<>();
		ByteBuffer buf = ByteBuffer.allocate(1024);
		
		while(true) {
			buf.clear();
			try {
				this.channel.receive(buf);
			}
			catch(IOException e) {
				e.printStackTrace();
				return;
			}
			
			try(ByteArrayInputStream bais = new ByteArrayInputStream(buf.array(), 0, buf.position());
				ObjectInputStream ois = new ObjectInputStream(bais);){
					/* A string means that the results have finished */
					Object tmp = ois.readUnshared();
					if(tmp instanceof String && ((String) tmp).equals("[END]")) {
						System.out.println("Ricevuti tutti i punteggi!");
						break;
					}
					
					@SuppressWarnings("unchecked")
					Pair<String, Integer> result = (Pair<String, Integer>) tmp;
					
					results.put(result.getFirst(), result.getSecond());
					System.out.println("Ho ricevuto: "+result);
					
					this.gui.executeJS("window", true).asObject().setProperty("results", results); // Aggiorno il binding
					this.gui.executeJS("updateChart('" + result.getFirst() + "');"); // Asynchronously update the chart
			}
			catch(IOException | ClassNotFoundException e) {
				e.printStackTrace();
			}
		}

		
		try {
			this.channel.close();
		}
		catch(IOException e) {
			e.printStackTrace();
		}
	}

}
