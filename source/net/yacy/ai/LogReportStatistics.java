/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.logging.*;
import java.util.zip.*;
import net.yacy.cora.util.LogRedaction;

/** Bounded local counters, not a raw-log archive. Uses the existing scheduler for checkpoints. */
final class LogReportStatistics extends Handler {
    static final int RETENTION_DAYS = 65;
    private static final int MAX_HOURS = RETENTION_DAYS*24+1;
    private final Path file;
    private final NavigableMap<Long, Bucket> buckets = new TreeMap<>();
    private Instant heartbeat;
    private Instant saved;
    private static final class Bucket {
        final long[][] minutes = new long[60][4]; // entries, warnings, errors, monitored milliseconds
        final List<String> samples = new ArrayList<>(); // at most 3 errors, 3 warnings, 1 other; 320 chars each
    }

    LogReportStatistics(Path directory) throws IOException {
        file = directory.resolve(".operational-statistics.gz");
        if (Files.exists(file)) try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            if (in.readInt()!=1) throw new IOException("Unknown statistics version");
            int count=in.readInt(); if(count<0 || count>MAX_HOURS) throw new IOException("Invalid statistics size");
            for(int i=0;i<count;i++) {
                long hour=in.readLong(); Bucket b=new Bucket();
                for(long[] minute:b.minutes) for(int j=0;j<4;j++) { minute[j]=in.readLong(); if(minute[j]<0) throw new IOException("Invalid counter"); }
                int samples=in.readInt(); if(samples<0 || samples>7) throw new IOException("Invalid sample count");
                for(int s=0;s<samples;s++) { String value=in.readUTF(); if(value.length()>360) throw new IOException("Invalid sample length"); b.samples.add(value); }
                buckets.put(hour,b);
            }
        }
    }

    private Bucket bucket(long second) { return buckets.computeIfAbsent(Math.floorDiv(second,3600), k->new Bucket()); }

    @Override public synchronized void publish(LogRecord record) {
        if (!isLoggable(record)) return;
        long second=record.getInstant().getEpochSecond();
        Bucket b=bucket(second); int minute=(int)Math.floorMod(Math.floorDiv(second,60),60);
        int severity=record.getLevel().intValue()>=Level.SEVERE.intValue()?2:record.getLevel().intValue()>=Level.WARNING.intValue()?1:0;
        b.minutes[minute][0]++; if(severity>0) b.minutes[minute][severity]++;
        String prefix=severity==2?"E ":severity==1?"W ":"I ";
        long existing=b.samples.stream().filter(s->s.startsWith(prefix)).count();
        if(existing<(severity==0?1:3)) {
            String text=LogRedaction.redact(String.valueOf(record.getLoggerName())+": "+record.getMessage()).replaceAll("[\\r\\n\\t]", " ");
            b.samples.add(prefix+record.getInstant()+" "+text.substring(0,Math.min(280,text.length())));
        }
        // Bound memory even if a persist operation fails.
        while(buckets.size()>MAX_HOURS) buckets.pollFirstEntry();
    }

    /** Missing heartbeats/restarts are unknown coverage, never inferred from a quiet log. */
    synchronized void checkpoint(Instant now, boolean force) throws IOException {
        if(heartbeat!=null && !now.isBefore(heartbeat) && Duration.between(heartbeat,now).getSeconds()<=90) {
            Instant cursor=heartbeat;
            while(cursor.isBefore(now)) {
                Instant end=Instant.ofEpochSecond(Math.floorDiv(cursor.getEpochSecond(),60)*60+60);
                if(end.isAfter(now)) end=now;
                Bucket b=bucket(cursor.getEpochSecond());
                int minute=(int)Math.floorMod(Math.floorDiv(cursor.getEpochSecond(),60),60);
                b.minutes[minute][3]=Math.min(60000,b.minutes[minute][3]+Duration.between(cursor,end).toMillis()); cursor=end;
            }
        }
        heartbeat=now;
        buckets.headMap(Math.floorDiv(now.minusSeconds(RETENTION_DAYS*86400L).getEpochSecond(),3600),false).clear();
        if(!force && saved!=null && Duration.between(saved,now).getSeconds()<300) return;
        Files.createDirectories(file.getParent()); Path temp=Files.createTempFile(file.getParent(),".statistics-",".tmp");
        try {
            try(DataOutputStream out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(1); out.writeInt(buckets.size());
                for(Map.Entry<Long,Bucket> entry:buckets.entrySet()) {
                    out.writeLong(entry.getKey()); for(long[] minute:entry.getValue().minutes) for(long n:minute) out.writeLong(n);
                    out.writeInt(entry.getValue().samples.size()); for(String sample:entry.getValue().samples) out.writeUTF(sample);
                }
            }
            try { Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING); }
            saved=now;
        } finally { Files.deleteIfExists(temp); }
    }

    synchronized Snapshot snapshot(Instant start, Instant end) {
        long entries=0,warnings=0,errors=0,covered=0; Instant first=null,last=null;
        List<String> samples=new ArrayList<>();
        for(Map.Entry<Long,Bucket> entry:buckets.subMap(Math.floorDiv(start.getEpochSecond(),3600),true,Math.floorDiv(end.minusNanos(1).getEpochSecond(),3600),true).entrySet()) {
            for(int i=0;i<60;i++) {
                Instant minute=Instant.ofEpochSecond(entry.getKey()*3600+i*60);
                if(minute.isBefore(start) || !minute.isBefore(end)) continue;
                long[] n=entry.getValue().minutes[i]; entries+=n[0]; warnings+=n[1]; errors+=n[2]; covered+=n[3];
                if(n[0]>0 || n[3]>0) { if(first==null) first=minute; last=minute.plusSeconds(60); }
            }
            for(String sample:entry.getValue().samples) {
                Instant at=Instant.parse(sample.split(" ",3)[1]); if(!at.isBefore(start) && at.isBefore(end)) samples.add(sample);
            }
        }
        samples.sort(Comparator.comparingInt((String s)->s.startsWith("E ")?0:s.startsWith("W ")?1:2)
                .thenComparing(Comparator.reverseOrder()));
        return new Snapshot(entries,warnings,errors,covered,first,last,samples);
    }

    static final class Snapshot {
        final long entries,warnings,errors,coveredMillis;
        final Instant first,last;
        final List<String> samples;
        Snapshot(long entries,long warnings,long errors,long covered,Instant first,Instant last,List<String> samples) {
            this.entries=entries;this.warnings=warnings;this.errors=errors;this.coveredMillis=covered;this.first=first;this.last=last;this.samples=samples;
        }
        String metadata(Instant start,Instant end,ZoneId zone) {
            long missing=Math.max(0,Duration.between(start,end).toMillis()-coveredMillis);
            java.time.format.DateTimeFormatter display=java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm XXX").withZone(zone);
            return "## Datengrundlage (deterministisch)\n\nAngeforderter / ausgewerteter Zeitraum: "+display.format(start)+" bis "+display.format(end)+" (Ende exklusiv); Zeitzone: "+zone.getId()+".\n"
                +"Verfügbare erfasste Logeinträge: "+entries+"; Warnungen: "+warnings+"; Fehler: "+errors+".\n"
                +"Beobachtete Daten von / bis: "+(first==null?"keine":display.format(first))+" / "+(last==null?"keine":display.format(last))+".\n"
                +"Überwachte Sekunden: "+coveredMillis/1000+"; Datenlücke / nicht nachgewiesene Abdeckung (Sekunden): "+missing/1000+".\n"
                +"Quelle: lokale kompakte Betriebsstatistik, 65 Tage Aufbewahrung, Minutenzähler; keine vollständigen Rohlogs. "
                +"Bei Neustart, Ausfall, Erstaktivierung oder verzögertem Checkpoint sind Daten unvollständig. Keine Aussagen über fehlende Zeitabschnitte.\n\n";
        }
    }
    @Override public void flush() { }
    @Override public void close() { }
}
