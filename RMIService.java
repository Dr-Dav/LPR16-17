package com.texttwist.server;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.HashMap;

import com.texttwist.client.RemoteUser;

public class RMIService extends UnicastRemoteObject implements RMITwist {
	private static final long serialVersionUID = 1L;
	private StorageService users;
	private HashMap<String, RemoteUser> callbacks;

	public RMIService(HashMap<String, RemoteUser> callbacks, StorageService storage) throws RemoteException {
		super();
		this.users = storage;
		this.callbacks = callbacks;
	}
	
	@Override
	public boolean register(String username, String password) throws RemoteException {
		// Sync not necessary - done by ReliableStorage itself
		return (password.length() == 0 || username.contains(" ") || username.length() > 30) ? false : this.users.addNewUser(username, password);
	}

	@Override
	public boolean login(String username, String password, RemoteUser stub) throws RemoteException {
		// Sync on this.users not necessary
		if(!this.users.checkUser(username, password)) { // Cambio l'equals o cerco il lower/higher? (Per la password) - CAMBIATO L'EQUALS
			return false;
		}
		synchronized(this.callbacks) { // Come prima
			return this.callbacks.put(username.toLowerCase(), stub) == null; // Non ci devono essere vecchie associazioni
		}
	}
	
	@Override
	public boolean logout(String username) throws RemoteException {
		synchronized(this.callbacks) { // Come prima
			if(this.callbacks.remove(username.toLowerCase()) == null)
				return false;
		}
		System.out.println("Logged out "+username);
		this.users.suggestRemove(username);
		return true;
	}

}
