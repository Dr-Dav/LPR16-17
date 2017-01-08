package com.texttwist.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ServerConfiguration {
	private int RMIPort;
	private int TCPPort;
	private int UDPPort;
	private InetAddress registryHostname;
	private int maxActiveMatches;
	private int maxUserCacheDimension;
	private String path;
	
	public ServerConfiguration(String filename) throws FileNotFoundException, IllegalArgumentException {
		this.path = new File("").getAbsolutePath();
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
		else if(name.equalsIgnoreCase("maxactivematches")) {
			try {
				this.maxActiveMatches = new Integer(value).intValue();
			}
			catch(NumberFormatException e) {
				System.err.println("Invalid value for \"MaxActiveMatches\" option. Using default 10.");
				this.maxActiveMatches = 10;
			}
			if(this.RMIPort > 65535) {
				System.err.println("Invalid value for \"MaxActiveMatches\" option. Using default 10.");
				this.maxActiveMatches = 10;
			}
		}
		else if(name.equalsIgnoreCase("registryhostname")) {
			try {
				this.registryHostname = InetAddress.getByName(value);
			}
			catch(UnknownHostException e) {
				System.err.println("Invalid IP for \"RegistryHostname\". Using default \"localhost\"");
				try {
					this.registryHostname = InetAddress.getLocalHost();
				} catch (UnknownHostException e1) {
					throw new RuntimeException("Could not resolve localhost.");
				}
			}
		}
		else if(name.equalsIgnoreCase("maxusercachedim")) {
			try {
				this.maxUserCacheDimension = new Integer(value).intValue();
			}
			catch(NumberFormatException e) {
				System.err.println("Invalid value for \"MaxUserCacheDim\" option. Using default 20.");
				this.maxUserCacheDimension = 20;
			}
			if(this.RMIPort > 65535) {
				System.err.println("Invalid value for \"MaxUserCacheDim\" option. Using default 20.");
				this.maxUserCacheDimension = 20;
			}
		}
		else if(name.equalsIgnoreCase("binaryroot")) {
			Pattern pattern = Pattern.compile("("+value+")$", Pattern.CASE_INSENSITIVE);
			Matcher matcher = pattern.matcher(this.path);
			if(matcher.find()) {
				this.path = matcher.replaceFirst(""); // Remove the subdirs from the base dirs
			}
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
	public InetAddress getRegistryHostname() {
		return registryHostname;
	}

	/**
	 * @return the maxActiveMatches
	 */
	public int getMaxActiveMatches() {
		return maxActiveMatches;
	}

	/**
	 * @return the maxUserCacheDimension
	 */
	public int getMaxUserCacheDimension() {
		return maxUserCacheDimension;
	}
	
	public String getBaseDir() {
		return path;
	}
	
}
