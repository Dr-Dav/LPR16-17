package com.texttwist.client;

public interface AccountManager extends Injectable {
	public void register(String username, String password);
	public void login(String username, String password);
	public void logout();
}
