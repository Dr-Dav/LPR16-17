package com.texttwist.server;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;

public class Main {

	public static void main(String[] args) {
		TwistServer server;
		ServerConfiguration conf;
		try {
			conf = new ServerConfiguration("serverConfig.conf");
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return;
		}
		System.out.println("Canonical path: " + conf.getBaseDir());
		try {
			server = new TwistServer(conf);
		} catch (IOException e) {
			System.err.println("An error occurred while starting the server: ");
			e.printStackTrace();
			return;
		}
		ExecutorService executor = Executors.newSingleThreadExecutor();
		executor.submit(new Runnable() {
			@Override
			public void run() {
				try(BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
					while(!input.readLine().equals("exit"));
					server.exit();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});
		server.start();
		executor.shutdown();
		try {
			executor.awaitTermination(100, TimeUnit.DAYS); // Huge Timeout, as we want to close the server manually
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

}
