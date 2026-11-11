package io.github.ticketing.catalog.repository

import io.github.ticketing.catalog.domain.Venue
import org.springframework.data.repository.Repository
import java.util.Optional

interface VenueRepository : Repository<Venue, Long> {
    fun findById(id: Long): Optional<Venue>

    fun <S : Venue> save(entity: S): S

    fun existsByName(name: String): Boolean
}
