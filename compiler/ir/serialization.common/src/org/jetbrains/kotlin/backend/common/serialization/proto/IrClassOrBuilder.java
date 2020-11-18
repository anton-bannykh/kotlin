// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: compiler/ir/serialization.common/src/KotlinIr.proto

package org.jetbrains.kotlin.backend.common.serialization.proto;

public interface IrClassOrBuilder extends
    // @@protoc_insertion_point(interface_extends:org.jetbrains.kotlin.backend.common.serialization.proto.IrClass)
    org.jetbrains.kotlin.protobuf.MessageLiteOrBuilder {

  /**
   * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclarationBase base = 1;</code>
   */
  boolean hasBase();
  /**
   * <code>required .org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclarationBase base = 1;</code>
   */
  org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclarationBase getBase();

  /**
   * <code>required int32 name = 2;</code>
   */
  boolean hasName();
  /**
   * <code>required int32 name = 2;</code>
   */
  int getName();

  /**
   * <code>optional .org.jetbrains.kotlin.backend.common.serialization.proto.IrValueParameter this_receiver = 3;</code>
   */
  boolean hasThisReceiver();
  /**
   * <code>optional .org.jetbrains.kotlin.backend.common.serialization.proto.IrValueParameter this_receiver = 3;</code>
   */
  org.jetbrains.kotlin.backend.common.serialization.proto.IrValueParameter getThisReceiver();

  /**
   * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrTypeParameter type_parameter = 4;</code>
   */
  java.util.List<org.jetbrains.kotlin.backend.common.serialization.proto.IrTypeParameter> 
      getTypeParameterList();
  /**
   * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrTypeParameter type_parameter = 4;</code>
   */
  org.jetbrains.kotlin.backend.common.serialization.proto.IrTypeParameter getTypeParameter(int index);
  /**
   * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrTypeParameter type_parameter = 4;</code>
   */
  int getTypeParameterCount();

  /**
   * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclaration declaration = 5;</code>
   */
  java.util.List<org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclaration> 
      getDeclarationList();
  /**
   * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclaration declaration = 5;</code>
   */
  org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclaration getDeclaration(int index);
  /**
   * <code>repeated .org.jetbrains.kotlin.backend.common.serialization.proto.IrDeclaration declaration = 5;</code>
   */
  int getDeclarationCount();

  /**
   * <code>repeated int32 super_type = 6 [packed = true];</code>
   */
  java.util.List<java.lang.Integer> getSuperTypeList();
  /**
   * <code>repeated int32 super_type = 6 [packed = true];</code>
   */
  int getSuperTypeCount();
  /**
   * <code>repeated int32 super_type = 6 [packed = true];</code>
   */
  int getSuperType(int index);

  /**
   * <code>optional int64 this_receiver_symbol = 7;</code>
   *
   * <pre>
   * for IC
   * </pre>
   */
  boolean hasThisReceiverSymbol();
  /**
   * <code>optional int64 this_receiver_symbol = 7;</code>
   *
   * <pre>
   * for IC
   * </pre>
   */
  long getThisReceiverSymbol();

  /**
   * <code>repeated int64 type_parameter_symbol = 8;</code>
   */
  java.util.List<java.lang.Long> getTypeParameterSymbolList();
  /**
   * <code>repeated int64 type_parameter_symbol = 8;</code>
   */
  int getTypeParameterSymbolCount();
  /**
   * <code>repeated int64 type_parameter_symbol = 8;</code>
   */
  long getTypeParameterSymbol(int index);

  /**
   * <code>repeated int64 declaration_symbol = 9;</code>
   */
  java.util.List<java.lang.Long> getDeclarationSymbolList();
  /**
   * <code>repeated int64 declaration_symbol = 9;</code>
   */
  int getDeclarationSymbolCount();
  /**
   * <code>repeated int64 declaration_symbol = 9;</code>
   */
  long getDeclarationSymbol(int index);
}