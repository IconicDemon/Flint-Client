import wallpaper from "../../assets/wallpapers/java_edition.jpg";
import { FlintMark } from "../../ui/Logo";

export default function StorePage() {
  return (
    <div className="page-body store-page">
      <section className="store-hero">
        <img src={wallpaper} alt="" className="store-hero-art" />
        <div className="store-hero-shade" />
        <div className="store-hero-content">
          <FlintMark size={40} />
          <span className="eyebrow">Flint Store</span>
          <h1>Not open yet</h1>
          <p>There's nothing for sale today, and nothing on this page is a placeholder product. When the store opens, it will be announced in Flint's news on the Play page.</p>
        </div>
      </section>
    </div>
  );
}
