package dev.nikhil.incidentops.service;

import dev.nikhil.incidentops.config.IncidentopsProperties;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** Round-robin assignment for new incidents, and "next person in line" for escalations. In-memory cursor. */
@Component
public class OnCallRotation {
    private final List<String> responders;
    private final AtomicInteger cursor = new AtomicInteger();

    public OnCallRotation(IncidentopsProperties props) { this.responders = List.copyOf(props.oncall()); }

    public String next() { return responders.get(Math.floorMod(cursor.getAndIncrement(), responders.size())); }

    public String after(String current) {
        int idx = responders.indexOf(current);
        return responders.get((idx + 1) % responders.size());
    }

    public List<String> responders() { return responders; }
}
