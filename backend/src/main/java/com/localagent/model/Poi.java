package com.localagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "poi")
public class Poi {
    @Id
    private UUID id;
    @Column(nullable = false)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PoiType type;
    private String subtype;
    private String address;
    private double lng;
    private double lat;
    private int durationMinutes;
    private int avgPrice;
    private double rating;
    private boolean kidFriendly;
    private boolean lowCalorie;
    private boolean indoor;
    private boolean social;
    private boolean ticketProblem;
    private boolean seatProblem;
    private String sourceProvider;
    private String sourcePoiId;

    public Poi() {
    }

    public Poi(String name, PoiType type, String subtype, String address, double lng, double lat,
               int durationMinutes, int avgPrice, double rating, boolean kidFriendly,
               boolean lowCalorie, boolean indoor, boolean social, boolean ticketProblem,
               boolean seatProblem) {
        this(name, type, subtype, address, lng, lat, durationMinutes, avgPrice, rating, kidFriendly,
                lowCalorie, indoor, social, ticketProblem, seatProblem, "", "");
    }

    public Poi(String name, PoiType type, String subtype, String address, double lng, double lat,
               int durationMinutes, int avgPrice, double rating, boolean kidFriendly,
               boolean lowCalorie, boolean indoor, boolean social, boolean ticketProblem,
               boolean seatProblem, String sourceProvider, String sourcePoiId) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.type = type;
        this.subtype = subtype;
        this.address = address;
        this.lng = lng;
        this.lat = lat;
        this.durationMinutes = durationMinutes;
        this.avgPrice = avgPrice;
        this.rating = rating;
        this.kidFriendly = kidFriendly;
        this.lowCalorie = lowCalorie;
        this.indoor = indoor;
        this.social = social;
        this.ticketProblem = ticketProblem;
        this.seatProblem = seatProblem;
        this.sourceProvider = sourceProvider;
        this.sourcePoiId = sourcePoiId;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public PoiType getType() { return type; }
    public String getSubtype() { return subtype; }
    public String getAddress() { return address; }
    public double getLng() { return lng; }
    public double getLat() { return lat; }
    public int getDurationMinutes() { return durationMinutes; }
    public int getAvgPrice() { return avgPrice; }
    public double getRating() { return rating; }
    public boolean isKidFriendly() { return kidFriendly; }
    public boolean isLowCalorie() { return lowCalorie; }
    public boolean isIndoor() { return indoor; }
    public boolean isSocial() { return social; }
    public boolean isTicketProblem() { return ticketProblem; }
    public boolean isSeatProblem() { return seatProblem; }
    public String getSourceProvider() { return sourceProvider; }
    public String getSourcePoiId() { return sourcePoiId; }
}
