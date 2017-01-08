package com.texttwist.client;
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface RemoteUser extends Remote {
	public static final String RMIPrefix = "RMI-";
	
	public void invite(String creator, String multicastIP) throws RemoteException;
	public void removeInvite(String creator, String multicastIP) throws RemoteException;
}
