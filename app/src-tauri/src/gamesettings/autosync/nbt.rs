//! Minecraft's NBT as the game keeps servers.dat and hotbar.nbt: uncompressed, big-endian, one named root
//! compound. Checked in the game's own code: 26.3 `ServerList` and `HotbarManager` read and write them with
//! `NbtIo.read(Path)` / `NbtIo.write`, 1.21.1 the same, and 1.8.9 reads servers.dat with
//! `CompressedStreamTools.read(File)` (a plain `DataInputStream`, no gzip).
//!
//! Tags are kept as they were read (strings stay in Java's modified UTF-8, numbers as their bits), so a file
//! written back holds every tag Flint doesn't know, byte for byte.

/// Minecraft refuses NBT nested deeper than this (`NbtAccounter`).
const MAX_DEPTH: usize = 512;

#[derive(Clone, Debug, PartialEq)]
pub enum Tag {
    Byte(i8),
    Short(i16),
    Int(i32),
    Long(i64),
    Float(u32),
    Double(u64),
    ByteArray(Vec<u8>),
    /// Java's modified UTF-8, as stored.
    Str(Vec<u8>),
    /// The element type, then the elements.
    List(u8, Vec<Tag>),
    Compound(Compound),
    IntArray(Vec<i32>),
    LongArray(Vec<i64>),
}

impl Tag {
    fn id(&self) -> u8 {
        match self {
            Tag::Byte(_) => 1,
            Tag::Short(_) => 2,
            Tag::Int(_) => 3,
            Tag::Long(_) => 4,
            Tag::Float(_) => 5,
            Tag::Double(_) => 6,
            Tag::ByteArray(_) => 7,
            Tag::Str(_) => 8,
            Tag::List(..) => 9,
            Tag::Compound(_) => 10,
            Tag::IntArray(_) => 11,
            Tag::LongArray(_) => 12,
        }
    }
}

/// A compound's named tags, in file order.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Compound(pub Vec<(Vec<u8>, Tag)>);

impl Compound {
    pub fn get(&self, name: &str) -> Option<&Tag> {
        self.0.iter().find(|(n, _)| n == name.as_bytes()).map(|(_, t)| t)
    }

    pub fn set(&mut self, name: &str, tag: Tag) {
        match self.0.iter_mut().find(|(n, _)| n == name.as_bytes()) {
            Some((_, slot)) => *slot = tag,
            None => self.0.push((name.as_bytes().to_vec(), tag)),
        }
    }

    pub fn text(&self, name: &str) -> Option<&[u8]> {
        match self.get(name) {
            Some(Tag::Str(bytes)) => Some(bytes),
            _ => None,
        }
    }
}

/// A whole file: the root compound and its name (empty in every file Minecraft writes).
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Root {
    pub name: Vec<u8>,
    pub body: Compound,
}

struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8], String> {
        let end = self.at.checked_add(n).filter(|end| *end <= self.bytes.len()).ok_or("the file ends early")?;
        let out = &self.bytes[self.at..end];
        self.at = end;
        Ok(out)
    }

    fn u8(&mut self) -> Result<u8, String> {
        Ok(self.take(1)?[0])
    }

    fn u16(&mut self) -> Result<u16, String> {
        Ok(u16::from_be_bytes(self.take(2)?.try_into().unwrap()))
    }

    fn i32(&mut self) -> Result<i32, String> {
        Ok(i32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }

    fn i64(&mut self) -> Result<i64, String> {
        Ok(i64::from_be_bytes(self.take(8)?.try_into().unwrap()))
    }

    fn string(&mut self) -> Result<Vec<u8>, String> {
        let len = self.u16()? as usize;
        Ok(self.take(len)?.to_vec())
    }

    /// A length, checked against what is left so a damaged file can't ask for a huge allocation.
    fn len(&mut self, each: usize) -> Result<usize, String> {
        let n = self.i32()?;
        let n = usize::try_from(n).map_err(|_| "a negative length")?;
        if n.saturating_mul(each) > self.bytes.len() - self.at {
            return Err("a length past the end of the file".into());
        }
        Ok(n)
    }

    fn payload(&mut self, id: u8, depth: usize) -> Result<Tag, String> {
        if depth > MAX_DEPTH {
            return Err("nested too deeply".into());
        }
        Ok(match id {
            1 => Tag::Byte(self.u8()? as i8),
            2 => Tag::Short(self.u16()? as i16),
            3 => Tag::Int(self.i32()?),
            4 => Tag::Long(self.i64()?),
            5 => Tag::Float(self.i32()? as u32),
            6 => Tag::Double(self.i64()? as u64),
            7 => {
                let n = self.len(1)?;
                Tag::ByteArray(self.take(n)?.to_vec())
            }
            8 => Tag::Str(self.string()?),
            9 => {
                let kind = self.u8()?;
                let n = self.len(1)?;
                if n > 0 && !(1..=12).contains(&kind) {
                    return Err("a list of an unknown type".into());
                }
                let mut items = Vec::with_capacity(n.min(4096));
                for _ in 0..n {
                    items.push(self.payload(kind, depth + 1)?);
                }
                Tag::List(kind, items)
            }
            10 => Tag::Compound(self.compound(depth + 1)?),
            11 => {
                let n = self.len(4)?;
                Tag::IntArray((0..n).map(|_| self.i32()).collect::<Result<_, _>>()?)
            }
            12 => {
                let n = self.len(8)?;
                Tag::LongArray((0..n).map(|_| self.i64()).collect::<Result<_, _>>()?)
            }
            _ => return Err(format!("an unknown tag type {id}")),
        })
    }

    fn compound(&mut self, depth: usize) -> Result<Compound, String> {
        let mut out = Compound::default();
        loop {
            let id = self.u8()?;
            if id == 0 {
                return Ok(out);
            }
            let name = self.string()?;
            let tag = self.payload(id, depth)?;
            out.0.push((name, tag));
        }
    }
}

/// Reads a whole file: a named root compound and nothing after it. A compressed file, one cut short, or one
/// with anything else in it is refused.
pub fn read(bytes: &[u8]) -> Result<Root, String> {
    let mut reader = Reader { bytes, at: 0 };
    if reader.u8()? != 10 {
        return Err("not an uncompressed NBT file".into());
    }
    let name = reader.string()?;
    let body = reader.compound(0)?;
    if reader.at != bytes.len() {
        return Err("data after the end of the file".into());
    }
    Ok(Root { name, body })
}

fn put_string(out: &mut Vec<u8>, bytes: &[u8]) {
    let len = u16::try_from(bytes.len()).unwrap_or(u16::MAX);
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(&bytes[..len as usize]);
}

fn put_payload(out: &mut Vec<u8>, tag: &Tag) {
    match tag {
        Tag::Byte(v) => out.push(*v as u8),
        Tag::Short(v) => out.extend_from_slice(&v.to_be_bytes()),
        Tag::Int(v) => out.extend_from_slice(&v.to_be_bytes()),
        Tag::Long(v) => out.extend_from_slice(&v.to_be_bytes()),
        Tag::Float(v) => out.extend_from_slice(&v.to_be_bytes()),
        Tag::Double(v) => out.extend_from_slice(&v.to_be_bytes()),
        Tag::ByteArray(v) => {
            out.extend_from_slice(&(v.len() as i32).to_be_bytes());
            out.extend_from_slice(v);
        }
        Tag::Str(v) => put_string(out, v),
        Tag::List(kind, items) => {
            out.push(if items.is_empty() { *kind } else { items[0].id() });
            out.extend_from_slice(&(items.len() as i32).to_be_bytes());
            items.iter().for_each(|t| put_payload(out, t));
        }
        Tag::Compound(c) => put_compound(out, c),
        Tag::IntArray(v) => {
            out.extend_from_slice(&(v.len() as i32).to_be_bytes());
            v.iter().for_each(|n| out.extend_from_slice(&n.to_be_bytes()));
        }
        Tag::LongArray(v) => {
            out.extend_from_slice(&(v.len() as i32).to_be_bytes());
            v.iter().for_each(|n| out.extend_from_slice(&n.to_be_bytes()));
        }
    }
}

fn put_compound(out: &mut Vec<u8>, compound: &Compound) {
    for (name, tag) in &compound.0 {
        out.push(tag.id());
        put_string(out, name);
        put_payload(out, tag);
    }
    out.push(0);
}

/// The file as Minecraft writes it.
pub fn write(root: &Root) -> Vec<u8> {
    let mut out = vec![10];
    put_string(&mut out, &root.name);
    put_compound(&mut out, &root.body);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Root {
        let server = Compound(vec![
            (b"name".to_vec(), Tag::Str(b"Hub".to_vec())),
            (b"ip".to_vec(), Tag::Str(b"hub.example.invalid".to_vec())),
            (b"hidden".to_vec(), Tag::Byte(0)),
            (b"acceptedCodeOfConduct".to_vec(), Tag::Int(-1)),
        ]);
        let body = Compound(vec![
            (b"servers".to_vec(), Tag::List(10, vec![Tag::Compound(server)])),
            (b"empty".to_vec(), Tag::List(0, Vec::new())),
            (b"nums".to_vec(), Tag::IntArray(vec![1, -2])),
            (b"longs".to_vec(), Tag::LongArray(vec![i64::MIN])),
            (b"bytes".to_vec(), Tag::ByteArray(vec![1, 2, 3])),
            (b"f".to_vec(), Tag::Float(1.5f32.to_bits())),
            (b"d".to_vec(), Tag::Double(0.1f64.to_bits())),
            (b"s".to_vec(), Tag::Short(-7)),
            (b"l".to_vec(), Tag::Long(42)),
            // Modified UTF-8: NUL as two bytes, which plain UTF-8 never has.
            (b"odd".to_vec(), Tag::Str(vec![0xc0, 0x80])),
        ]);
        Root { name: Vec::new(), body }
    }

    #[test]
    fn a_file_reads_and_writes_back_byte_for_byte() {
        let bytes = write(&sample());
        let back = read(&bytes).unwrap();
        assert_eq!(back, sample());
        assert_eq!(write(&back), bytes);
        // The layout Minecraft writes: a compound tag with an empty name, then its tags, then the end tag.
        assert_eq!(&bytes[..3], &[10, 0, 0]);
        assert_eq!(*bytes.last().unwrap(), 0);
    }

    #[test]
    fn damaged_compressed_and_padded_files_are_refused() {
        let bytes = write(&sample());
        for cut in [1, 5, bytes.len() / 2, bytes.len() - 1] {
            assert!(read(&bytes[..cut]).is_err(), "cut at {cut}");
        }
        assert!(read(&[bytes.as_slice(), &[0]].concat()).is_err(), "trailing bytes");
        assert!(read(&[0x1f, 0x8b, 8, 0]).is_err(), "gzip");
        // A list claiming two billion entries in a tiny file.
        let mut huge = vec![10, 0, 0, 9, 0, 1, b'x', 1];
        huge.extend_from_slice(&i32::MAX.to_be_bytes());
        huge.push(0);
        assert!(read(&huge).is_err());
        let mut deep = vec![10, 0, 0];
        for _ in 0..600 {
            deep.extend_from_slice(&[10, 0, 1, b'x']);
        }
        deep.extend(std::iter::repeat_n(0, 601));
        assert!(read(&deep).unwrap_err().contains("deeply"));
    }
}
