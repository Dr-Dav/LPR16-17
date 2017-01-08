package com.texttwist.server;
import java.rmi.Remote;
import java.rmi.RemoteException;

import com.texttwist.client.RemoteUser;

public interface RMITwist extends Remote {
	public static final String NAME = "TextTwist";
	
	public boolean register(String username, String password) throws RemoteException;
	public boolean login(String username, String password, RemoteUser stub) throws RemoteException;
	public boolean logout(String username) throws RemoteException;
}
