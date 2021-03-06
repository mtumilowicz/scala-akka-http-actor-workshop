package app.domain.venue.error

import app.domain.error.DomainError
import app.domain.venue.VenueId

case class VenueNotFoundError(venueId: VenueId) extends DomainError