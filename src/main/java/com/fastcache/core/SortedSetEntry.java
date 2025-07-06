package com.fastcache.core;

import java.io.Serializable;
import java.util.Objects;

/**
 * Represents an entry in a sorted set with a member and its associated score.
 * Entries are ordered by score first, then by member lexicographically.
 */
public class SortedSetEntry implements Comparable<SortedSetEntry>, Serializable {
    private static final long serialVersionUID = 1L;
    private final String member;
    private final double score;
    
    public SortedSetEntry(String member, double score) {
        this.member = member;
        this.score = score;
    }
    
    public String getMember() {
        return member;
    }
    
    public double getScore() {
        return score;
    }
    
    @Override
    public int compareTo(SortedSetEntry other) {
        // First compare by score
        int scoreComparison = Double.compare(this.score, other.score);
        if (scoreComparison != 0) {
            return scoreComparison;
        }
        
        // If scores are equal, compare by member lexicographically
        return this.member.compareTo(other.member);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        
        SortedSetEntry that = (SortedSetEntry) obj;
        return Double.compare(that.score, score) == 0 && 
               Objects.equals(member, that.member);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(member, score);
    }
    
    @Override
    public String toString() {
        return "SortedSetEntry{member='" + member + "', score=" + score + "}";
    }
} 