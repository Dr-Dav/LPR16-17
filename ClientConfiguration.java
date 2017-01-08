package com.texttwist.client;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

public class ClientConfiguration {
	private int RMIPort;
	private int TCPPort;
	private int UDPPort;
	private String serverAddress;
	
	public ClientConfiguration(String filename) throws FileNotFoundException, IllegalArgumentException {
		this.serverAddress = "127.0.0.1";
		
		try(BufferedReader file = new BufferedReader(new FileReader(filename))) {
			String line;
			while((line = file.readLine()) != null) {
				line = line.trim(); 
				if(line.matches("[a-zA-Z]+( )*=( )*\\S+;?")) {
					String[] config = line.split("=");
					addConfig(config);
					System.out.println(config[0].trim() + " = " + config[1].trim());
				}
			}
		}
		catch(FileNotFoundException e) {
			throw new FileNotFoundException(filename);
		}
		catch(IOException e) {
			e.printStackTrace();
		}
	}
	
	private void addConfig(String[] option) {
		String name = option[0].trim();
		String value = option[1].trim();
		
		if(name.equalsIgnoreCase("rmiport")) {
			try {
				this.RMIPort = new Integer(value).intValue();
			}
			catch(NumberFormatException e) {
				System.err.println("Invalid value for \"RMIPort\" option. Using default 1099.");
				this.RMIPort = 1099;
			}
			if(this.RMIPort > 65535) {
				System.err.println("Invalid value for \"RMIPort\" option. Using default 1099.");
				this.RMIPort = 1099;
			}
		}
		else if(name.equalsIgnoreCase("tcpport")) {
			try {
				this.TCPPort = new Integer(value).intValue();
			}
			catch(NumberFormatException e) {
				System.err.println("Invalid value for \"TCPPort\" option. Using default 8000.");
				this.TCPPort = 8000;
			}
			if(this.RMIPort > 65535) {
				System.err.println("Invalid value for \"TCPPort\" option. Using default 8000.");
				this.TCPPort = 8000;
			}
		}
		else if(name.equalsIgnoreCase("udpport")) {
			try {
				this.UDPPort = new Integer(value).intValue();
			}
			catch(NumberFormatException e) {
				System.err.println("Invalid value for \"UDPPort\" option. Using default 1099.");
				this.UDPPort = 1099;
			}
			if(this.RMIPort > 65535) {
				System.err.println("Invalid value for \"UDPPort\" option. Using default 1099.");
				this.UDPPort = 1099;
			}
		}
		else if(name.equalsIgnoreCase("serveraddress")) {
			this.serverAddress = value;
		}
	}
	

	/**
	 * @return the RMIPort
	 */
	public int getRMIPort() {
		return RMIPort;
	}

	/**
	 * @return the TCPPort
	 */
	public int getTCPPort() {
		return TCPPort;
	}

	/**
	 * @return the UDPPort
	 */
	public int getUDPPort() {
		return UDPPort;
	}

	/**
	 * @return the registryHostname
	 */
	public String getServerAddress() {
		return serverAddress;
	}

}