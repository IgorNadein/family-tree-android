package app.familygem.people

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.ArrayDeque

/** The original person ID remains stable; the second record is removed after its data moves. */
data class PersonMergePreview(
    val keepId: String,
    val removeId: String,
    val mergedJson: String,
    val addedNames: Int,
    val addedFacts: Int,
    val addedPhotos: Int,
    val addedNotes: Int,
    val addedSources: Int,
    val affectedFamilies: Int,
    val warnings: List<String>
)

/**
 * Pure, deterministic preview/apply. No database or Android interaction until confirmed.
 * Never silently chooses between contradictory scalar attributes, parents or spouses.
 * This is distinct from merging two version histories or inferring kinship by name.
 */
object PersonMerge {
    private val primaryLists = setOf("names", "eventsFacts", "media", "mediaRefs",
        "notes", "noteRefs", "sourceCitations", "referenceNumbers", "associations",
        "famc", "fams")
    private val familyRoles = listOf("husbandRefs", "wifeRefs", "childRefs")

    fun plan(json: String, keepId: String, removeId: String): PersonMergePreview {
        require(keepId != removeId) { "Choose two different people" }
        val tree = JsonParser.parseString(json).asJsonObject.deepCopy()
        val people = tree.getAsJsonArray("people")
            ?: throw IllegalArgumentException("This tree contains no people")
        val families = tree.getAsJsonArray("families") ?: JsonArray()
        val byId = linkedMapOf<String, JsonObject>()
        people.forEach { item ->
            val record = item.asJsonObject
            val id = record.get("id")?.asString ?: error("Person without an ID")
            require(byId.put(id, record) == null) { "Duplicate person ID " + id }
        }
        val keeper = byId[keepId] ?: throw IllegalArgumentException("Primary person not found")
        val donor = byId[removeId] ?: throw IllegalArgumentException("Second person not found")
        val originalIds = byId.keys.toSet()

        // A person must never become their own ancestor, spouse, or child.
        val descendants = linkedMapOf<String, MutableSet<String>>()
        val affected = linkedSetOf<String>()
        families.forEach { item ->
            val family = item.asJsonObject
            val familyId = family.get("id")?.asString ?: error("Family without an ID")
            val husbands = refs(family, "husbandRefs")
            val wives = refs(family, "wifeRefs")
            val children = refs(family, "childRefs")
            if (keepId in husbands + wives + children &&
                removeId in husbands + wives + children) {
                val a = roleOf(keepId, husbands, wives, children)
                val b = roleOf(removeId, husbands, wives, children)
                if (a != b || a == "spouse") {
                    throw IllegalArgumentException(
                        "Cannot merge: people have incompatible roles in family " + familyId)
                }
            }
            for (parent in husbands + wives)
                descendants.getOrPut(parent) { linkedSetOf() }.addAll(children)
            if (removeId in husbands + wives + children) affected += familyId
        }
        // Two independently recorded biological parent families may represent
        // competing genealogical hypotheses. Never silently combine them into
        // an apparent two-parentage conclusion.
        val keepParentFamilies = families.map { it.asJsonObject }
            .filter { keepId in refs(it, "childRefs") }
            .map { it.get("id").asString }.toSet() + refs(keeper, "famc")
        val removeParentFamilies = families.map { it.asJsonObject }
            .filter { removeId in refs(it, "childRefs") }
            .map { it.get("id").asString }.toSet() + refs(donor, "famc")
        require(keepParentFamilies.isEmpty() || removeParentFamilies.isEmpty() ||
            keepParentFamilies == removeParentFamilies) {
            "Conflicting parent families: review the parentage before merging"
        }
        fun isAncestor(ancestor: String, person: String): Boolean {
            val visited = mutableSetOf<String>()
            val remaining = ArrayDeque<String>()
            remaining.add(ancestor)
            while (remaining.isNotEmpty()) {
                val current = remaining.removeFirst()
                for (child in descendants[current].orEmpty()) {
                    if (child == person) return true
                    if (visited.add(child)) remaining.add(child)
                }
            }
            return false
        }
        require(!isAncestor(keepId, removeId) && !isAncestor(removeId, keepId)) {
            "Cannot merge an ancestor with their descendant"
        }

        // A contradictory SEX history is not a reliable identity anchor.
        require(knownSexValues(keeper).size <= 1 && knownSexValues(donor).size <= 1) {
            "One of the selected people has contradictory recorded sexes"
        }
        val keepSex = knownSex(keeper)
        val removeSex = knownSex(donor)
        require(keepSex == null || removeSex == null || keepSex == removeSex) {
            "Conflicting recorded sexes; review the people before merging"
        }

        val beforeCounts = counts(keeper)
        for ((field, incomingValue) in donor.entrySet()) {
            if (field == "id" || field == "chan") continue
            val existing = keeper.get(field)
            if (existing == null || existing.isJsonNull || isBlank(existing)) {
                keeper.add(field, incomingValue.deepCopy())
                continue
            }
            if (field in primaryLists && existing.isJsonArray && incomingValue.isJsonArray) {
                val combined = union(existing.asJsonArray, incomingValue.asJsonArray,
                    field == "famc" || field == "fams")
                keeper.add(field, combined)
            } else if (existing == incomingValue || isBlank(incomingValue)) {
                // Identical metadata needs no duplicate, empty metadata adds nothing.
            } else if (existing.isJsonObject && incomingValue.isJsonObject) {
                keeper.add(field, mergeObjects(existing.asJsonObject, incomingValue.asJsonObject, field))
            } else {
                throw IllegalArgumentException(
                    "Conflicting value for " + field + ": edit one record before merging")
            }
        }

        // Some older GEDCOM versions keep family links only on the family side.
        // Reconcile both directions without guessing relationships.
        families.forEach { item ->
            val family = item.asJsonObject
            val familyId = family.get("id").asString
            for (role in familyRoles) {
                val original = family.get(role)?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                val rewritten = JsonArray()
                val seen = mutableMapOf<String, JsonElement>()
                original.forEach { old ->
                    val newRef = old.deepCopy().asJsonObject
                    if (newRef.get("ref")?.asString == removeId)
                        newRef.addProperty("ref", keepId)
                    val id = newRef.get("ref")?.asString ?: error("Family reference without ID")
                    require(id in originalIds) { "Unresolved person reference " + id }
                    val prior = seen[id]
                    if (prior == null) {
                        seen[id] = newRef
                        rewritten.add(newRef)
                    } else require(prior == newRef) {
                        "Different relationship details for " + id + " in family " + familyId
                    }
                }
                family.add(role, rewritten)
            }
            val spouses = refs(family, "husbandRefs") + refs(family, "wifeRefs")
            val children = refs(family, "childRefs")
            require(!(keepId in spouses && keepId in children)) {
                "A person cannot be their own parent in family " + familyId
            }
            require(!(keepId in refs(family, "husbandRefs") &&
                keepId in refs(family, "wifeRefs"))) {
                "A person cannot be both spouses in family " + familyId
            }
            // Backfill missing links even if the incoming person lacked famc/fams.
            if (keepId in children) addFamilyRef(keeper, "famc", familyId)
            if (keepId in spouses) addFamilyRef(keeper, "fams", familyId)
        }

        val filtered = JsonArray()
        people.forEach { record ->
            if (record.asJsonObject.get("id")?.asString != removeId)
                filtered.add(record.deepCopy())
        }
        tree.add("people", filtered)
        // Person references can also occur in associations and imported GEDCOM
        // custom records. Do not rewrite free-text citations or historical notes.
        fun redirectReferences(value: JsonElement) {
            when {
                value.isJsonArray -> value.asJsonArray.forEach(::redirectReferences)
                value.isJsonObject -> {
                    val objectValue = value.asJsonObject
                    val reference = objectValue.get("ref")
                    if (reference != null && reference.isJsonPrimitive &&
                        reference.asJsonPrimitive.isString && reference.asString == removeId) {
                        objectValue.addProperty("ref", keepId)
                    }
                    objectValue.entrySet().forEach { redirectReferences(it.value) }
                }
            }
        }
        redirectReferences(tree)
        // Surviving person's extra family references must exist in this tree.
        val familyIds = families.map { it.asJsonObject.get("id").asString }.toSet()
        require(refs(keeper, "famc").all { it in familyIds } &&
            refs(keeper, "fams").all { it in familyIds }) {
            "One of the people refers to a missing family"
        }
        val afterCounts = counts(keeper)
        val warnings = mutableListOf<String>()
        for (tag in listOf("BIRT", "DEAT")) {
            val events = keeper.get("eventsFacts")?.takeIf { it.isJsonArray }?.asJsonArray
            val distinct = events?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
                ?.filter { it.get("tag")?.asString == tag }
                ?.map { it.toString() }?.distinct().orEmpty()
            if (distinct.size > 1) warnings +=
                (if (tag == "BIRT") "Different birth records were preserved"
                 else "Different death records were preserved")
        }
        val merged = tree.toString()
        // The installed GEDCOM parser must still accept the result.
        org.folg.gedcom.parser.JsonParser().fromJson(merged).createIndexes()
        return PersonMergePreview(keepId, removeId, merged,
            afterCounts[0] - beforeCounts[0], afterCounts[1] - beforeCounts[1],
            afterCounts[2] - beforeCounts[2], afterCounts[4] - beforeCounts[4],
            afterCounts[3] - beforeCounts[3], affected.size, warnings)
    }

    private fun roleOf(
        id: String, husbands: List<String>, wives: List<String>, children: List<String>
    ): String = when {
        id in husbands || id in wives -> "spouse"
        id in children -> "child"
        else -> ""
    }

    private fun refs(record: JsonObject, field: String): List<String> =
        record.get(field)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { ref -> ref.isJsonObject }?.asJsonObject
                ?.get("ref")?.asString } ?: emptyList()

    private fun knownSex(record: JsonObject): String? =
        knownSexValues(record).singleOrNull()

    private fun knownSexValues(record: JsonObject): List<String> =
        record.get("eventsFacts")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { event -> event.isJsonObject }?.asJsonObject }
            ?.filter { it.get("tag")?.asString == "SEX" }
            ?.mapNotNull { it.get("value")?.asString?.uppercase()?.takeIf { s -> s == "M" || s == "F" } }
            ?.distinct().orEmpty()

    private fun isBlank(value: JsonElement): Boolean = when {
        value.isJsonNull -> true
        value.isJsonArray -> value.asJsonArray.isEmpty
        value.isJsonObject -> value.asJsonObject.size() == 0
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString.isBlank()
        else -> false
    }

    private fun union(existing: JsonArray, incoming: JsonArray, reference: Boolean): JsonArray {
        val result = JsonArray()
        val seen = linkedMapOf<String, JsonElement>()
        (existing.toList() + incoming.toList()).forEach { element ->
            val key = if (reference) {
                require(element.isJsonObject && element.asJsonObject.has("ref")) {
                    "Invalid family reference" }
                element.asJsonObject.get("ref").asString
            } else element.toString()
            val prior = seen[key]
            if (prior == null) {
                seen[key] = element
                result.add(element.deepCopy())
            } else require(prior == element) {
                "Conflicting details for linked family " + key
            }
        }
        return result
    }

    private fun mergeObjects(a: JsonObject, b: JsonObject, path: String): JsonObject {
        val result = a.deepCopy()
        for ((key, value) in b.entrySet()) {
            val present = result.get(key)
            if (present == null || isBlank(present)) {
                result.add(key, value.deepCopy())
            } else if (present == value || isBlank(value)) {
                continue
            } else {
                throw IllegalArgumentException(
                    "Conflicting " + path + "." + key + ": review the cards before merging")
            }
        }
        return result
    }

    private fun addFamilyRef(person: JsonObject, key: String, familyId: String) {
        val old = person.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
        if (old.none { it.isJsonObject && it.asJsonObject.get("ref")?.asString == familyId }) {
            val entry = JsonObject()
            entry.addProperty("ref", familyId)
            old.add(entry)
        }
        person.add(key, old)
    }

    private fun counts(person: JsonObject): List<Int> = listOf(
        person.get("names")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0,
        person.get("eventsFacts")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0,
        (person.get("media")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0) +
            (person.get("mediaRefs")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0),
        person.get("sourceCitations")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0,
        (person.get("notes")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0) +
            (person.get("noteRefs")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0)
    )
}
