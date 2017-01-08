package com.texttwist.client;

import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;

import javax.swing.JFrame;
import javax.swing.WindowConstants;

import com.teamdev.jxbrowser.chromium.Browser;
import com.teamdev.jxbrowser.chromium.BrowserContext;
import com.teamdev.jxbrowser.chromium.BrowserContextParams;
import com.teamdev.jxbrowser.chromium.JSValue;
import com.teamdev.jxbrowser.chromium.events.ConsoleEvent;
import com.teamdev.jxbrowser.chromium.events.ConsoleListener;
import com.teamdev.jxbrowser.chromium.events.FinishLoadingEvent;
import com.teamdev.jxbrowser.chromium.events.LoadAdapter;
import com.teamdev.jxbrowser.chromium.events.ScriptContextAdapter;
import com.teamdev.jxbrowser.chromium.events.ScriptContextEvent;
import com.teamdev.jxbrowser.chromium.swing.BrowserView;
import com.texttwist.server.RMITwist;

public class GUI {
	private JFrame window;
	private Browser browser;
	private String baseUrl;
	private String dirName;
	private HashMap<String, ScriptContextAdapter> javaBindings;
	private TwistClient twistclient; // Keep a reference to the main client to close it when the user closes the window
	
	public GUI(String title, int width, int height, TwistClient twistclient) {
		this.twistclient = twistclient;
		
		this.window = new JFrame();
		this.window.setTitle(title);
		this.window.setSize(width, height);
		this.window.setVisible(true);
		this.window.setResizable(false);
		this.window.setLocationRelativeTo(null); // Centers the window
		this.window.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
		
		this.javaBindings = new HashMap<>();
		
		this.dirName = Integer.valueOf(ThreadLocalRandom.current().nextInt()).toString();
		this.browser = new Browser(new BrowserContext(new BrowserContextParams("browserData/"+this.dirName)));
		this.browser.addConsoleListener(new ConsoleListener() {
		    public void onMessage(ConsoleEvent event) {
		        System.err.println("[CONSOLE] " + event.getMessage());
		    }
		});
		
		this.window.add(new BrowserView(this.browser), BorderLayout.CENTER);
		
		this.baseUrl = "file://"+(new File("").getAbsolutePath())+"/res/html/";
	}
	
	public void close() {
		this.browser.dispose();
	}
	
	public void resize(int width, int height) {
		// TROPPO COSTOSO!
		/* Dimension dim = window.getSize();
		if(width <= dim.getWidth()) {
			while (dim.getWidth() >= width) {
				window.setSize((int)(dim.getWidth()-1), (int)(dim.getHeight()));
				dim = window.getSize();
	        }
		}
		if(width >= dim.getWidth()) {
			while (dim.getWidth() <= width) {
				window.setSize((int)(dim.getWidth()+1), (int)(dim.getHeight()));
				dim = window.getSize();
	        }
		}*/
		this.window.setSize(width, height);
	}
	
	public void resize(int width, int height, boolean center) {
		this.resize(width, height);
		if(center)
			this.window.setLocationRelativeTo(null);
	}
	
	public String currentUrl() {
		return this.browser.getURL();
	}
	
	/* Associa ogni interfaccia "Injectable" ad un oggetto JavaScript con lo stesso nome dell'interfaccia */
	public void bindJavaObjects(Injectable... injectables) {
		this.browser.addLoadListener(new LoadAdapter() {
			@Override
			public void onFinishLoadingFrame(FinishLoadingEvent event) {
				if(event.isMainFrame()) {
					JSValue value = browser.executeJavaScriptAndReturnValue("window");
					for(Injectable i : injectables) {
						Class<?>[] interfaces = i.getClass().getInterfaces();
						for(Class<?> interf : interfaces) {	
							if(Injectable.class.isAssignableFrom(interf)) {
								value.asObject().setProperty(interf.getSimpleName(), interf.cast(i));
								//System.out.println("Trovata interfaccia: "+interf.getSimpleName());
							}
						}
					}
				}
			}
		});
	}
	
	public <T> void bindJavaObject(String name, T obj) {
		this.javaBindings.put(name, new ScriptContextAdapter() {
		    @Override
		    public void onScriptContextCreated(ScriptContextEvent event) {
		        Browser browser = event.getBrowser();
		        JSValue window = browser.executeJavaScriptAndReturnValue("window");
		        window.asObject().setProperty(name, obj);
		    }
		});
		this.browser.addScriptContextListener(this.javaBindings.get(name));
	}
	
	public void removeJavaBinding(String name) {
		if(this.javaBindings.get(name) != null)
			this.browser.removeScriptContextListener(this.javaBindings.get(name));
	}
	
	public void executeJS(String js) {
		browser.executeJavaScript(js);
	}
	
	public JSValue executeJS(String js, boolean blocking) {
		if(blocking)
			return browser.executeJavaScriptAndReturnValue(js);
		browser.executeJavaScript(js);
		return null;
	}
	
	public void openPage(String url) {
		this.browser.loadURL(this.baseUrl + url);
		this.window.setTitle((this.browser.getTitle().equals("about:blank")) ? "TextTwist" : this.browser.getTitle());
	}
	
	public void setTitle(String title) {
		this.window.setTitle(title);
	}

	private void deleteDir(File dir) {
		if(dir.isDirectory()) {
			String[] children = dir.list();
			for (int i = 0; i < children.length; i++) {
				deleteDir(new File(dir, children[i]));
			}
		}
		dir.delete();
		//System.out.println("The directory is deleted.");
	}
	
	public void setLogoutOnClose(RMITwist twistServer, String username) {
		this.window.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
		this.window.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				deleteDir(new File("browserData/"+dirName)); // Deletes the instance of the browser's file
				try {
					System.out.println(twistServer.logout(username));
					close();
					twistclient.close();
				} catch (RemoteException e1) {
					twistclient.close();
				}
			}
		});
	}
}
