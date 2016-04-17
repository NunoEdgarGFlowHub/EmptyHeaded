#ifndef _EHVECTOR_H_
#define _EHVECTOR_H_

#include "layout/UINTEGER.hpp"
#include "layout/BITSET.hpp"

/*
Vectors are laid flat in memory as follows

---------------------------------------------------------
Vector | Indices (uint/bitset/block) | Annotations
---------------------------------------------------------
*/

struct EHVector{
  //Find the index of a data elem.
  template <class A, class M>
  static inline uint32_t indexOf(
    const uint32_t data) 
  {
      (void) data;
      return 0;
  }

  template <class M>
  static inline Meta* get_meta(
    const M * const restrict memoryBuffer,
    const BufferIndex& restrict bufferIndex
  ){
    return new 
      (memoryBuffer->get_address(bufferIndex)) Meta();
  }

  //calls index of then calls get below.
  template <class A, class M>
  static inline A get(
    const uint32_t data,
    const M * const restrict memoryBuffer,
    const BufferIndex& restrict bufferIndex) {
    const Meta * const restrict meta = get_meta<M>(memoryBuffer,bufferIndex);
    if(meta->get_density() >= VECTOR_DENSITY_THRESHOLD){
      return BITSET:: template get<A,M>(data,meta,memoryBuffer,bufferIndex);
    } 
    return UINTEGER:: template get<A,M>(data,meta,memoryBuffer,bufferIndex);
  }

  //look up a data value
  template <class A, class M>
  static inline A get(
    const uint32_t index,
    const uint32_t data,
    const M * const restrict memoryBuffer,
    const BufferIndex& restrict bufferIndex)
  {
    const Meta * const restrict meta = get_meta<M>(memoryBuffer,bufferIndex);
    if(meta->get_density() >= VECTOR_DENSITY_THRESHOLD){
      return BITSET:: template get<A,M>(index,data,meta,memoryBuffer,bufferIndex);
    }
    return UINTEGER:: template get<A,M>(index,data,meta,memoryBuffer,bufferIndex);
  }

  //set an annotation value
  template <class A, class M>
  static inline void set(
    const uint32_t index,
    const uint32_t data, 
    const A& restrict value,
    const M * const restrict memoryBuffer,
    const BufferIndex& restrict bufferIndex) 
  {
    const Meta * const restrict meta = get_meta<M>(memoryBuffer,bufferIndex);
    (void) data;
    if(meta->get_density() >= VECTOR_DENSITY_THRESHOLD){
      BITSET:: template set<A,M>(
        index,
        data,
        value,
        meta,
        memoryBuffer,
        bufferIndex);
      return;
    }
    UINTEGER:: template set<A,M>(
      index,
      data,
      value,
      meta,
      memoryBuffer,
      bufferIndex);
  }

  //look up a data value
  template <class A, class M>
  static inline bool contains(const uint32_t key) {
    (void) key;
    return false;
  }

  //mutable loop (returns data and index)
  template <class A, class M, typename F>
  static inline void foreach(
    F f,
    const M * const restrict memoryBuffer,
    const BufferIndex& restrict bufferIndex)
  {
    const Meta * const restrict meta = get_meta<M>(memoryBuffer,bufferIndex);
    
    switch(meta->type){
      case type::BITSET : 
        BITSET:: template foreach<A,M>(f,meta,memoryBuffer,bufferIndex);
      break;
      case type::UINTEGER :
        UINTEGER:: template foreach<A,M>(f,meta,memoryBuffer,bufferIndex);
      break;
      default:
        std::cout << "TYPE ERROR" << std::endl;
        throw;
      break;
    }
  }

    //mutable loop (returns data and index)
  template <class M, typename F>
  static inline void foreach_index(
    F f,
    const M * const restrict memoryBuffer,
    const BufferIndex& restrict bufferIndex)
  {
    const Meta * const restrict meta = get_meta<M>(memoryBuffer,bufferIndex);

    switch(meta->type){
      case type::BITSET : 
        BITSET:: template foreach_index<M>(f,meta,memoryBuffer,bufferIndex);
      break;
      case type::UINTEGER :
        UINTEGER:: template foreach_index<M>(f,meta,memoryBuffer,bufferIndex);
      break;
      default:
        std::cout << "TYPE ERROR" << std::endl;
        throw;
      break;
    }
  }

    //mutable loop (returns data and index)
  template <class M, typename F>
  static inline void parforeach_index(
    F f,
    const M * const restrict memoryBuffer,
    const BufferIndex& restrict bufferIndex)
  {
    const Meta * const restrict meta = get_meta<M>(memoryBuffer,bufferIndex);
    if(meta->get_density() >= VECTOR_DENSITY_THRESHOLD){
      BITSET:: template parforeach_index<M>(
        f,
        meta,
        memoryBuffer,
        bufferIndex);
      return;
    }
    UINTEGER:: template parforeach_index<M>(
      f,
      meta,
      memoryBuffer,
      bufferIndex);
  }

  template <class A>
  static inline size_t get_num_bytes(
    const uint32_t * const data,
    const size_t len)
  {
    if(len > MIN_BITSET_LENGTH){
      const double density =  (double)(data[len-1]-data[0])/len;
      if(density >= VECTOR_DENSITY_THRESHOLD){
        const size_t num_words = len > 0 ? BITSET::word_index(data[len-1])-BITSET::word_index(data[0])+1 : 0;
        return sizeof(Meta)+(num_words*64*sizeof(A))+(num_words*sizeof(uint64_t));
      }
    }
    return sizeof(Meta)+len*(sizeof(uint32_t)+sizeof(A));
  }

  //constructors
  static inline void from_array(
    uint8_t* restrict buffer,    
    const uint32_t * const restrict data,
    const size_t len) 
  {
    Meta* meta = new(buffer) Meta();
    meta->cardinality = len;
    meta->start = 0;
    meta->end = 0;
    meta->type = type::UINTEGER;
    if(len == 0)
      return;
    else{
      meta->start = *data;
      meta->end = *(data+(len-1));
    }

    if(len > MIN_BITSET_LENGTH){
      const double density =  (double)(data[len-1]-data[0])/len;
      if(density >= VECTOR_DENSITY_THRESHOLD){
        BITSET::from_vector(buffer+sizeof(Meta),data,len);
        meta->type = type::BITSET;
        return;
      }
    }
    UINTEGER::from_vector(buffer+sizeof(Meta),data,len);
    meta->type = type::UINTEGER;
  }

  //constructors
  template <class A>
  static inline void from_array(
    uint8_t* restrict buffer,    
    const uint32_t * const data,
    const A * const restrict values,
    const size_t len,
    const size_t anno_offset) 
  {
    Meta* meta = new(buffer) Meta();
    meta->cardinality = len;
    meta->start = 0;
    meta->end = 0;
    meta->type = type::UINTEGER;
    if(len == 0)
      return;
    else{
      meta->start = *data;
      meta->end = *(data+(len-1));
    }

    if(len > MIN_BITSET_LENGTH){
      const double density = (double)(data[len-1]-data[0])/len;
      if(density >= VECTOR_DENSITY_THRESHOLD){
        BITSET::from_vector<A>(buffer+sizeof(Meta),data,values,len);
        meta->type = type::BITSET;
        return;
      }
    }
    UINTEGER::from_vector<A>(buffer+sizeof(Meta),data,values,len);
    meta->type = type::UINTEGER;
  }
};

#endif