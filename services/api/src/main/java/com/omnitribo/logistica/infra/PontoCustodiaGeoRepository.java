package com.omnitribo.logistica.infra;

// Repositório dedicado a consultas geoespaciais de ponto de custódia.
// Toda chamada ST_DWithin / ST_Distance fica aqui — permite trocar PostGIS por
// Oracle Spatial neste único arquivo (ver ADR 0002).
// Implementação: F6 — Geolocalização.
public interface PontoCustodiaGeoRepository {}
