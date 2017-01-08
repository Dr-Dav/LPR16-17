package com.texttwist.client;

public interface GameManager extends Injectable {
	public boolean sendInvites(String data);
	public void refuseInvite(String creator);
	public void submitWords(String words);
	public void showInvites();
	public void startMatch(String creator, String multicast);
	public void getGlobalChart();
	public void resetSize();
	public void stopGame();
}
