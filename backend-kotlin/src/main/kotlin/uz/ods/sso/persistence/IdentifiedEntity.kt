package uz.ods.sso.persistence

import jakarta.persistence.Column
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Transient
import uz.ods.sso.shared.UuidV7
import uz.ods.sso.shared.newId
import java.util.UUID

@MappedSuperclass
abstract class IdentifiedEntity(
    prefix: String = "id",
) {
    @Id
    @Column(name = "internal_id", nullable = false, updatable = false, columnDefinition = "uuid")
    var internalId: UUID = UuidV7.next()

    @Column(name = "public_id", nullable = false, unique = true, updatable = false, length = 40)
    var publicId: String = newId(prefix)

    @get:Transient
    val id: String
        get() = publicId
}
