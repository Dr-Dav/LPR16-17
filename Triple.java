package com.texttwist.server;

public class Triple<F, S, T> {
	private F first;
	private S second;
	private T third;
	
	public Triple(F first, S second, T third) {
		this.first = first;
		this.second = second;
		this.third = third;
	}
	
	public F getFirst() {
		return first;
	}
	
	public S getSecond() {
		return second;
	}
	
	public T getThird() {
		return third;
	}
	
	@Override
	public boolean equals(Object o) {
		if(!Triple.class.isAssignableFrom(o.getClass()))
			return false;
		@SuppressWarnings("unchecked")
		Triple<F, S, T> t = (Triple<F, S, T>) o;
		return this.first.equals(t.getFirst()) && this.second.equals(t.getSecond()) && this.third.equals(t.getThird());
	}
}
