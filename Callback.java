package com.texttwist.client;
import java.rmi.RemoteException;
import java.rmi.server.RemoteObject;

public class Callback extends RemoteObject implements RemoteUser {
	private static final long serialVersionUID = 1L;
	private String username;
	private TwistClient main;
	
	public Callback(TwistClient main) {
		super();
		this.username = null;
		this.main = main;
	}
	
	public String getUsername() {
		return this.username;
	}
	
	public void setUsername(String username) {
		this.username = username;
	}

	@Override
	public void invite(String creator, String multicastIP) throws RemoteException {
		System.out.println("Callback di "+username+" da "+creator);
		if(creator.equalsIgnoreCase(this.username)) { // My own callback
			main.startMatch(creator, multicastIP);
		}
		else {
			main.addInvite(creator, multicastIP);
		}
	}

	// Multicast necessario perché gli inviti sono contenuti in una coppia
	@Override
	public void removeInvite(String creator, String multicastIP) throws RemoteException {
		System.out.println("L'invito di "+creator+" non c'è più");
		main.removeInvite(creator, multicastIP);
	}
	
}
