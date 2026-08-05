package com.omnitribo.geolocalizacao.infra;

// Repositório dedicado a consultas geoespaciais de check-in.
// Toda chamada ST_DWithin / ST_Distance fica aqui — permite trocar PostGIS por
// Oracle Spatial neste único arquivo (ver ADR 0002).
// Implementação: F6 — Geolocalização.
public interface CheckinGeoRepository {}
