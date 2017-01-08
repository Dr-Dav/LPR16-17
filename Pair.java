package com.texttwist.client;

import java.io.Serializable;

public class Pair<F extends Serializable, S extends Serializable> implements Serializable {
	private static final long serialVersionUID = 1L;
	private F first;
	private S second;
	
	public Pair(F first, S second) {
		this.first = first;
		this.second = second;
	}
	
	public F getFirst() {
		return first;
	}
	
	public S getSecond() {
		return second;
	}
	
	@Override
	public boolean equals(Object o) {
		if(!Pair.class.isAssignableFrom(o.getClass())) {
			return false;
		}
		@SuppressWarnings("unchecked")
		Pair<F, S> t = (Pair<F, S>) o;
		return this.first.equals(t.getFirst()) && this.second.equals(t.getSecond());
	}
	
	public String toString() {
		return "First: " + this.first.toString() + " Second: " + this.second.toString(); 
	}
}
