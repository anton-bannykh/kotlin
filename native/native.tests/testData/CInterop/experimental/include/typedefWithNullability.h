// KT-71674: typedefs whose underlying type has a nullability specifier.
typedef int * _Null_unspecified NullUnspecifiedIntPtr;
typedef int * _Nonnull NonnullIntPtr;
typedef int * _Nullable NullableIntPtr;
typedef int * PlainIntPtr;

void useNonnull(NonnullIntPtr p);
