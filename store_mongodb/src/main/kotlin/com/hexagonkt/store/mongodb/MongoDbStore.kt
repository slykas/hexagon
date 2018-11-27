package com.hexagonkt.store.mongodb

import com.hexagonkt.helpers.filterEmpty
import com.hexagonkt.helpers.logger
import com.hexagonkt.store.IndexOrder
import com.hexagonkt.store.IndexOrder.ASCENDING
import com.hexagonkt.store.Mapper
import com.hexagonkt.store.Store
import com.mongodb.client.MongoDatabase
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.*
import com.mongodb.client.model.Filters.eq
import org.bson.Document
import org.bson.conversions.Bson
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties

class MongoDbStore <T : Any, K : Any>(
    override val type: KClass<T>,
    override val key: KProperty1<T, K>,
    override val name: String = type.java.simpleName,
    private val database: MongoDatabase,
    override val mapper: Mapper<T> = MongoDbMapper(type, key)) : Store<T, K> {

    private val collection: MongoCollection<Document> = this.database.getCollection(name)

    private val fields: List<String> by lazy {
        logger.time ("REFLECT") { type.declaredMemberProperties } // TODO This is *VERY* slow
            .map { it.name }
    }

    init {
        if (key.name != "_id")
            createIndex(true, key.name to ASCENDING)
    }

    override fun createIndex(unique: Boolean, fields: List<Pair<String, IndexOrder>>): String {
        val indexes = fields.map {
            if (it.second == ASCENDING) Indexes.ascending(it.first)
            else Indexes.descending(it.first)
        }

        val name = fields.joinToString("_") { it.first + "_" + it.second.toString().toLowerCase() }
        val compoundIndex = Indexes.compoundIndex(indexes)
        val indexOptions = IndexOptions().unique(unique).background(true).name(name)

        return collection.createIndex(compoundIndex, indexOptions)
    }

    override fun insertOne(instance: T): K {
        collection.insertOne(map(instance))
        return key.get(instance)
    }

    override fun insertMany(instances: List<T>): List<K> {
        collection.insertMany(instances.map { instance -> map(instance) })
        return instances.map { key.get(it) }
    }

    override fun saveOne(instance: T): K? {
        val filter = createKeyFilter(key.get(instance))
        val options = UpdateOptions().upsert(true)
        val replaceOptions = ReplaceOptions.createReplaceOptions(options)
        val result = collection.replaceOne(filter, map(instance), replaceOptions)
        val upsertedId = result.upsertedId
        return if (upsertedId == null) null
            else mapper.fromStore(key.name, upsertedId as Any) as K
    }

    override fun saveMany(instances: List<T>): List<K?> =
        instances.map(this::saveOne)

    override fun replaceOne(instance: T): Boolean {
        val document = map(instance)
        val filter = createKeyFilter(key.get(instance))
        val result = collection.replaceOne(filter, document)
        return result.modifiedCount == 1L
    }

    override fun replaceMany(instances: List<T>): List<T> =
        instances.mapNotNull { if (replaceOne(it)) it else null }

    override fun updateOne(key: K, updates: Map<String, *>): Boolean {
        val filter = createKeyFilter(key)
        val update = createUpdate(updates)
        val result = collection.updateOne(filter, update)
        return result.modifiedCount == 1L
    }

    override fun updateMany(filter: Map<String, List<*>>, updates: Map<String, *>): Long {
        val updateFilter = createFilter(filter)
        val update = createUpdate(updates)
        val result = collection.updateOne(updateFilter, update)
        return result.modifiedCount
    }

    override fun deleteOne(id: K): Boolean {
        val filter = createKeyFilter(id)
        val result = collection.deleteOne(filter)
        return result.deletedCount == 1L
    }

    override fun deleteMany(filter: Map<String, List<*>>): Long {
        val deleteFilter = createFilter(filter)
        val result = collection.deleteMany(deleteFilter)
        return result.deletedCount
    }

    override fun findOne(key: K): T? {
        val result = collection.find(createKeyFilter(key)).first()?.filterEmpty() ?: error("")
        return mapper.fromStore(result as Map<String, Any>)
    }

    override fun findOne(key: K, fields: List<String>): Map<String, *> {
        val filter = createKeyFilter(key)
        val result = collection
            .find(filter)
            .projection(createProjection(fields))
            .first()?.filterEmpty() ?: error("")

        return result.mapValues { mapper.toStore(it.key, it.value as Any) }
    }

    override fun findMany(
        filter: Map<String, List<*>>,
        limit: Int?,
        skip: Int?,
        sort: Map<String, Boolean>): List<T> {

        val findFilter = createFilter(filter)
//        val sort = createSort(sort)
        val result = collection
            .find(findFilter)
//            .sortedBy(sort)
            .into(ArrayList())

        return result.map { mapper.fromStore(it.filterEmpty()) }
    }

    override fun findMany(
        filter: Map<String, List<*>>,
        fields: List<String>,
        limit: Int?,
        skip: Int?,
        sort: Map<String, Boolean>): List<Map<String, *>> {

//        val projection = createProjection(fields)

        val findFilter = createFilter(filter)
//        val sort = createSort(sort)
        val result = collection
            .find(findFilter)
//            .projection(createProjection(fields))
//            .sortedBy(sort)
            .into(ArrayList())

//        return result.map { mapper.fromStore(it.filterEmpty()) }
        TODO()
    }

    override fun count(filter: Map<String, List<*>>): Long {
        val countFilter = createFilter(filter)
        return collection.countDocuments(countFilter)
    }

    override fun drop() {
        collection.drop()
    }

    private fun map(instance: T): Document = Document(mapper.toStore(instance))

    private fun createKeyFilter(key: K) = eq("_id", key)

    private fun createFilter(filter: Map<String, List<*>>): Bson =
        filter
            .filterEmpty()
            .filter {
                val key = it.key
                val firstKeySegment = key.split ("\\.")[0]
                fields.contains (firstKeySegment)
            }
            .map {
                val key = it.key
                val value = it.value

                if (value.size > 1) key to mapOf("\$in" to value)
                else key to value[0]
            }
            .map {
                if (it.first == key.name) "_id" to it.second
                else it
            }
            .toMap()
            .toDocument()

    private fun createUpdate (update: Map<String, *>): Bson =
        Updates.combine(
            update
                .filterEmpty()
                .mapValues { mapper.toStore(it.key, it.value as Any) }
                .map { Updates.set(it.key, it.value) }
        )

    private fun createProjection (fields: List<String>): Bson =
        if(fields.isEmpty ()) Document()
        else
            fields
                .asSequence()
                .filter { fields.contains(it) }
                .map { it to 1 }
                .toMap()
                .toDocument()
                .append("_id", 0)

    private fun createSort(fields : Map<String, Boolean>): Bson =
        fields
            .filter { fields.contains (it.key) }
            .mapValues { if (it.value) -1 else 1 }
            .toDocument()

    private fun Map<String, *>.toDocument() = Document(this)
}
