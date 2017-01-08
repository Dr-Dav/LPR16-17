package com.texttwist.server;

public class User implements Comparable<User> {
	//private static final long serialVersionUID = 1L;
	private String username;
	private String password; // Plain text for now!
	private int points;
	
	public User(String username, String password) {
		this.username = username;
		this.password = password;
		this.points = 0;
	}
	
	public String getUsername() {
		return username;
	}
	
	public String getPassword() {
		return password;
	}
	
	public int getPoints() {
		return points;
	}
	
	public void setPoints(int points) {
		this.points = points;
	}
	
	public void addPoints(int points) {
		this.points += points;
	}
	
	@Override
	public boolean equals(Object o) {
		if(!User.class.isAssignableFrom(o.getClass()))
			return false;
		User u = (User) o;
		/*
		 * Without password: used to verify the existence of an user
		 * For example when registering (to see if an username is already taken) or when logging out
		 */
		if(u.getPassword() == "")
			return this.username.equalsIgnoreCase(u.getUsername());
		return this.username.equalsIgnoreCase(u.getUsername()) && this.password.equals(u.getPassword());
	}

	@Override
	public int compareTo(User o) {
		return this.username.compareToIgnoreCase(o.username);
	}
}
