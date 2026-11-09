package dev.nikhil.incidentops.service;

import dev.nikhil.incidentops.api.Dtos.IncidentView;
import dev.nikhil.incidentops.api.Dtos.TimelineEntry;

/** Published inside the transaction; the SSE hub only forwards it after commit. */
public record IncidentChanged(IncidentView incident, TimelineEntry event) {}
