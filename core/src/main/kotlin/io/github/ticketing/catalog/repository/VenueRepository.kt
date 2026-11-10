package io.github.ticketing.catalog.repository

import io.github.ticketing.catalog.domain.Venue
import org.springframework.data.jpa.repository.JpaRepository

interface VenueRepository : JpaRepository<Venue, Long> {
    fun existsByName(name: String): Boolean
}
